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
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed best-effort dispatch handoff.
 */
public final class RedisTransportDispatchHandoff implements TransportDispatchHandoff,
        AdapterMailboxConsumerRegistry,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DISPATCH;
    public static final int DEFAULT_MAX_QUEUED_ITEMS_PER_QUEUE = 100_000;

    private static final long POLL_SLEEP_MILLIS = 50L;
    private static final String CONSUMER_EVIDENCE_VERSION = "v3";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisKeyedBlockingQueueStore readyQueue;
    private final String namespacePrefix;
    private final Map<String, LocalMailboxConsumerEvidence> localConsumers = new ConcurrentHashMap<>();
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
        this.commands = connection.sync();
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
    public List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        String adapterMailboxKey = normalizeRequired(batch.adapterMailboxKey(), "adapterMailboxKey");
        List<DispatchMessage> itemsToOffer = batch.items();
        if (!running.get()) {
            return itemsToOffer.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "dispatch handoff is stopped"))
                    .toList();
        }
        if (currentMailboxConsumerEvidence(adapterMailboxKey, System.currentTimeMillis()) == null) {
            return itemsToOffer.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "adapter mailbox has no active consumer"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(itemsToOffer.size());
        for (DispatchMessage item : itemsToOffer) {
            KeyedQueueOfferResult result = readyQueue.offer(
                    adapterMailboxKey,
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
        LocalMailboxConsumerEvidence local = localConsumers.get(mailboxKey);
        if (local == null || local.availableUntilEpochMillis() <= System.currentTimeMillis()) {
            if (local != null) {
                localConsumers.remove(mailboxKey, local);
            }
            return List.of();
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            if (currentMailboxConsumerEvidence(mailboxKey, System.currentTimeMillis()) == null) {
                return List.of();
            }
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
    public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        Objects.requireNonNull(lease, "lease");
        LocalMailboxConsumerEvidence local = new LocalMailboxConsumerEvidence(
                lease.adapterMailboxKey(),
                lease.consumerId(),
                lease.generation(),
                lease.availableUntilEpochMillis()
        );
        localConsumers.put(lease.adapterMailboxKey(), local);
        commands.hset(mailboxConsumersKey(), lease.adapterMailboxKey(), encodeConsumerEvidence(lease));
        commands.zadd(mailboxConsumerDeadlinesKey(), lease.availableUntilEpochMillis(), lease.adapterMailboxKey());
    }

    @Override
    public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        Objects.requireNonNull(lease, "lease");
        localConsumers.computeIfPresent(lease.adapterMailboxKey(),
                (ignored, current) -> lease.consumerId().equals(current.consumerId()) ? null : current);
        MailboxConsumerEvidence current = currentMailboxConsumerEvidence(lease.adapterMailboxKey(), System.currentTimeMillis());
        if (current == null || !lease.consumerId().equals(current.consumerId())) {
            return;
        }
        commands.hdel(mailboxConsumersKey(), lease.adapterMailboxKey());
        commands.zrem(mailboxConsumerDeadlinesKey(), lease.adapterMailboxKey());
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
        commands.hdel(mailboxConsumersKey(), normalizedMailboxKey);
        commands.zrem(mailboxConsumerDeadlinesKey(), normalizedMailboxKey);
        while (!readyQueue.drain(normalizedMailboxKey, 1000).isEmpty()) {
            // Drain all test data for this mailbox.
        }
    }

    void claimConsumerForTest(String adapterMailboxKey, String consumerId) {
        publishMailboxConsumerAvailability(new AdapterMailboxConsumerAvailability(
                adapterMailboxKey,
                consumerId,
                1L,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5L)
        ));
    }

    private MailboxConsumerEvidence currentMailboxConsumerEvidence(String adapterMailboxKey, long nowEpochMillis) {
        String normalizedMailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        MailboxConsumerEvidence evidence = decodeConsumerEvidence(
                commands.hget(mailboxConsumersKey(), normalizedMailboxKey)
        );
        if (evidence == null) {
            commands.zrem(mailboxConsumerDeadlinesKey(), normalizedMailboxKey);
            return null;
        }
        if (evidence.availableUntilEpochMillis() <= nowEpochMillis) {
            commands.hdel(mailboxConsumersKey(), normalizedMailboxKey);
            commands.zrem(mailboxConsumerDeadlinesKey(), normalizedMailboxKey);
            return null;
        }
        return evidence;
    }

    private String mailboxConsumersKey() {
        return namespacePrefix + ":mailbox-consumers";
    }

    private String mailboxConsumerDeadlinesKey() {
        return namespacePrefix + ":mailbox-consumer-deadlines";
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value) {
        return new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String encodeConsumerEvidence(AdapterMailboxConsumerAvailability lease) {
        return String.join("|",
                CONSUMER_EVIDENCE_VERSION,
                encodeToken(lease.consumerId()),
                Long.toString(lease.generation()),
                Long.toString(lease.availableUntilEpochMillis())
        );
    }

    private static MailboxConsumerEvidence decodeConsumerEvidence(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 4 || !CONSUMER_EVIDENCE_VERSION.equals(parts[0])) {
            return null;
        }
        return new MailboxConsumerEvidence(
                decodeToken(parts[1]),
                parseLong(parts[2]),
                parseLong(parts[3])
        );
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record MailboxConsumerEvidence(String consumerId,
                                           long generation,
                                           long availableUntilEpochMillis) {
    }

    private record LocalMailboxConsumerEvidence(String adapterMailboxKey,
                                                String consumerId,
                                                long generation,
                                                long availableUntilEpochMillis) {
    }
}
