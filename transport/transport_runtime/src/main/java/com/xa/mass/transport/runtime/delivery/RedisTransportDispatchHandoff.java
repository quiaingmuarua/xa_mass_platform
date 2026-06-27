package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.redis.queue.RedisKeyedBlockingQueueStore;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueNamespace;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueOptions;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed best-effort dispatch handoff.
 */
public final class RedisTransportDispatchHandoff implements TransportDispatchHandoff, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DISPATCH;
    public static final int DEFAULT_MAX_QUEUED_ITEMS_PER_QUEUE = 100_000;

    private static final long POLL_SLEEP_MILLIS = 50L;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisKeyedBlockingQueueStore readyQueue;
    private final String namespacePrefix;
    private final int maxQueuedItemsPerQueue;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TransportDispatchBatchCodec codec = new TransportDispatchBatchCodec();

    public RedisTransportDispatchHandoff(String redisUri,
                                         String namespacePrefix,
                                         int maxQueuedItemsPerQueue) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedItemsPerQueue,
                true
        );
    }

    RedisTransportDispatchHandoff(RedisClient redisClient,
                                  String namespacePrefix,
                                  int maxQueuedItemsPerQueue,
                                  boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                maxQueuedItemsPerQueue,
                ownsClient
        );
    }

    RedisTransportDispatchHandoff(StatefulRedisConnection<String, String> connection,
                                  String namespacePrefix,
                                  int maxQueuedItemsPerQueue) {
        this(null, connection, namespacePrefix, maxQueuedItemsPerQueue, false);
    }

    private RedisTransportDispatchHandoff(RedisClient redisClient,
                                          StatefulRedisConnection<String, String> connection,
                                          String namespacePrefix,
                                          int maxQueuedItemsPerQueue,
                                          boolean ownsClient) {
        if (maxQueuedItemsPerQueue <= 0) {
            throw new IllegalArgumentException("maxQueuedItemsPerQueue must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.maxQueuedItemsPerQueue = maxQueuedItemsPerQueue;
        this.ownsClient = ownsClient;
        this.readyQueue = new RedisKeyedBlockingQueueStore(
                connection,
                new RedisKeyedQueueNamespace(this.namespacePrefix + ":ready"),
                RedisKeyedQueueOptions.defaults(maxQueuedItemsPerQueue)
        );
    }

    @Override
    public List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items) {
        String queueKey = normalizeRequired(dispatchQueueKey, "dispatchQueueKey");
        List<DispatchMessage> itemsToOffer = normalizeItems(items);
        if (!running.get()) {
            return itemsToOffer.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "dispatch handoff is stopped"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(itemsToOffer.size());
        for (DispatchMessage item : itemsToOffer) {
            KeyedQueueOfferResult result = readyQueue.offer(
                    queueKey,
                    new KeyedQueueEntry(codec.encodeItem(item), item.createdAtEpochMillis()),
                    maxQueuedItemsPerQueue
            );
            DispatchOutcomeStatus outcomeStatus = switch (result.status()) {
                case ENQUEUED -> DispatchOutcomeStatus.QUEUED;
                case INVALID -> DispatchOutcomeStatus.INVALID;
                case UNAVAILABLE -> DispatchOutcomeStatus.UNAVAILABLE;
                case BACKPRESSURE_REJECTED -> DispatchOutcomeStatus.BACKPRESSURE;
            };
            boolean queued = outcomeStatus == DispatchOutcomeStatus.QUEUED;
            outcomes.add(DispatchOutcomeFactory.fromItem(
                    item,
                    outcomeStatus,
                    !queued,
                    queued ? null : result.reason()
            ));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public List<DispatchMessage> poll(String adapterMailboxKey,
                                          int maxItems,
                                          long timeoutMillis) throws InterruptedException {
        String mailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be greater than 0");
        }
        if (!running.get()) {
            return List.of();
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            List<DispatchMessage> items = pollReadyItems(mailboxKey, maxItems);
            if (!items.isEmpty()) {
                return items;
            }
            if (timeoutMillis <= 0L) {
                return List.of();
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return List.of();
            }
            Thread.sleep(Math.min(POLL_SLEEP_MILLIS, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        } while (running.get());
        return List.of();
    }

    private List<DispatchMessage> pollReadyItems(String adapterMailboxKey, int maxItems) {
        List<DispatchMessage> items = new ArrayList<>(maxItems);
        for (KeyedQueueEntry entry : readyQueue.drain(adapterMailboxKey, maxItems)) {
            try {
                items.add(codec.decodeItem(entry.value()));
            } catch (RuntimeException ignored) {
                // Corrupt handoff entries are dropped as store-local corruption.
            }
        }
        return List.copyOf(items);
    }

    @Override
    public void shutdown() {
        running.set(false);
        close();
    }

    @Override
    public void close() {
        if (connection.isOpen()) {
            connection.close();
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    int queuedBatches(String adapterMailboxKey) {
        return readyQueue.size(adapterMailboxKey);
    }

    long readyItemsForTest(String adapterMailboxKey) {
        return readyQueue.size(adapterMailboxKey);
    }

    void pushReadyItemForTest(String adapterMailboxKey, DispatchMessage item) {
        readyQueue.offer(adapterMailboxKey, new KeyedQueueEntry(codec.encodeItem(item), item.createdAtEpochMillis()),
                maxQueuedItemsPerQueue);
    }

    void pushRawReadyValueForTest(String adapterMailboxKey, String value) {
        readyQueue.offer(adapterMailboxKey, new KeyedQueueEntry(value, System.currentTimeMillis()), maxQueuedItemsPerQueue);
    }

    void clearForTest(String adapterMailboxKey) {
        String normalizedMailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        while (!readyQueue.drain(normalizedMailboxKey, 1000).isEmpty()) {
            // Drain all test data for this mailbox.
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static List<DispatchMessage> normalizeItems(List<DispatchMessage> items) {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        for (DispatchMessage item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
        }
        return List.copyOf(items);
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

}
