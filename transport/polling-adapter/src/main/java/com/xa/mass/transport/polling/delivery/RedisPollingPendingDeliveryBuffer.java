package com.xa.mass.transport.polling.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueNamespace;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed polling final-hop pull buffer.
 *
 * <p>This is adapter-local pending delivery storage, not the assigned-dispatch
 * mailbox handoff. Redis queue slots are keyed by adapter mailbox plus selected
 * worker, so a polling worker never destructively pops another worker's item.
 */
public final class RedisPollingPendingDeliveryBuffer implements PollingPendingDeliveryBuffer {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = InMemoryPollingPendingDeliveryBuffer.DEFAULT_MAX_QUEUED_ITEMS;
    public static final int DEFAULT_MAX_ITEMS_PER_WORKER =
            InMemoryPollingPendingDeliveryBuffer.DEFAULT_MAX_ITEMS_PER_WORKER;
    public static final String DEFAULT_NAMESPACE_PREFIX = "xa:mass:transport:polling-delivery:v1";

    private static final char SLOT_DELIMITER = '\u001F';

    private static final String OFFER_SCRIPT = """
            local queueKey = KEYS[1]
            local metaKey = KEYS[2]
            local activeQueuesKey = KEYS[3]
            local globalStatsKey = KEYS[4]
            local queueValue = ARGV[1]
            local createdAtEpochMillis = ARGV[2]
            local maxItemsPerWorker = tonumber(ARGV[3])
            local maxQueuedItems = tonumber(ARGV[4])
            local encodedKeyPart = ARGV[5]

            if (not queueKey) or (not queueValue) or (not createdAtEpochMillis) or
               (not maxItemsPerWorker) or (not maxQueuedItems) or (not encodedKeyPart) then
                redis.call('HINCRBY', globalStatsKey, 'invalidItems', 1)
                return {'INVALID', 'key and entry must not be null'}
            end

            if maxItemsPerWorker <= 0 then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_SLOT', 'worker pending delivery buffer is disabled'}
            end

            local queueLength = redis.call('LLEN', queueKey)
            if queueLength >= maxItemsPerWorker then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_SLOT', 'polling worker pending delivery buffer is full'}
            end

            local queuedItems = tonumber(redis.call('HGET', globalStatsKey, 'queuedItems') or '0')
            if queuedItems >= maxQueuedItems then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_GLOBAL', 'polling pending delivery backlog is full'}
            end

            redis.call('RPUSH', queueKey, queueValue)
            redis.call('HINCRBY', globalStatsKey, 'queuedItems', 1)
            redis.call('HINCRBY', globalStatsKey, 'enqueuedItems', 1)

            if queueLength == 0 then
                redis.call('SADD', activeQueuesKey, encodedKeyPart)
                redis.call('HSET', metaKey, 'oldestCreatedAtEpochMillis', createdAtEpochMillis)
            end

            return {'ENQUEUED'}
            """;

    private static final String DRAIN_SLOT_SCRIPT = """
            local queueKey = KEYS[1]
            local metaKey = KEYS[2]
            local activeQueuesKey = KEYS[3]
            local globalStatsKey = KEYS[4]
            local maxItems = tonumber(ARGV[1])
            local encodedKeyPart = ARGV[2]

            if (not queueKey) or (not maxItems) or (not encodedKeyPart) then
                return {'INVALID', 'key and maxItems must not be null'}
            end
            if maxItems <= 0 then
                return {'INVALID', 'maxItems must be positive'}
            end

            local drained = {}
            for i = 1, maxItems do
                local value = redis.call('LPOP', queueKey)
                if not value then
                    break
                end
                drained[#drained + 1] = value
            end

            local count = #drained
            if count == 0 then
                return {'EMPTY', '0'}
            end

            redis.call('HINCRBY', globalStatsKey, 'queuedItems', -count)
            redis.call('HINCRBY', globalStatsKey, 'drainedItems', count)

            local nextHead = redis.call('LINDEX', queueKey, 0)
            if nextHead then
                local delimiter = string.find(nextHead, '|', 1, true)
                if delimiter and delimiter > 1 then
                    redis.call('HSET', metaKey, 'oldestCreatedAtEpochMillis', string.sub(nextHead, 1, delimiter - 1))
                else
                    redis.call('HDEL', metaKey, 'oldestCreatedAtEpochMillis')
                end
            else
                redis.call('SREM', activeQueuesKey, encodedKeyPart)
                redis.call('DEL', metaKey)
            end

            local response = {'DRAINED', tostring(count)}
            for i = 1, count do
                response[#response + 1] = drained[i]
            end
            return response
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisKeyedQueueNamespace namespace;
    private final PollingDispatchRoutingItemCodec codec = new PollingDispatchRoutingItemCodec();
    private final boolean ownsClient;
    private final int maxQueuedItems;
    private final int maxItemsPerWorker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong localInvalidItems = new AtomicLong();
    private final AtomicLong localUnavailableItems = new AtomicLong();
    private final AtomicLong localShutdownClearedItems = new AtomicLong();

    public RedisPollingPendingDeliveryBuffer(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_ITEMS, DEFAULT_MAX_ITEMS_PER_WORKER);
    }

    public RedisPollingPendingDeliveryBuffer(String redisUri,
                                             String namespacePrefix,
                                             int maxQueuedItems,
                                             int maxItemsPerWorker) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedItems,
                maxItemsPerWorker,
                true
        );
    }

    RedisPollingPendingDeliveryBuffer(RedisClient redisClient,
                                      String namespacePrefix,
                                      int maxQueuedItems,
                                      int maxItemsPerWorker,
                                      boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                maxQueuedItems,
                maxItemsPerWorker,
                ownsClient
        );
    }

    RedisPollingPendingDeliveryBuffer(StatefulRedisConnection<String, String> connection,
                                      String namespacePrefix,
                                      int maxQueuedItems,
                                      int maxItemsPerWorker) {
        this(null, connection, namespacePrefix, maxQueuedItems, maxItemsPerWorker, false);
    }

    private RedisPollingPendingDeliveryBuffer(RedisClient redisClient,
                                              StatefulRedisConnection<String, String> connection,
                                              String namespacePrefix,
                                              int maxQueuedItems,
                                              int maxItemsPerWorker,
                                              boolean ownsClient) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        if (maxItemsPerWorker <= 0) {
            throw new IllegalArgumentException("maxItemsPerWorker must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespace = new RedisKeyedQueueNamespace(namespacePrefix);
        this.maxQueuedItems = maxQueuedItems;
        this.maxItemsPerWorker = maxItemsPerWorker;
        this.ownsClient = ownsClient;
    }

    @Override
    public List<DispatchOutcome> enqueue(String adapterMailboxKey, List<DispatchRoutingItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (DispatchRoutingItem item : items) {
            outcomes.add(enqueueOne(adapterMailboxKey, item));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public PollingPendingDeliveryPollResult poll(String adapterMailboxKey,
                                                 String authenticatedWorkerId,
                                                 int maxItems,
                                                 long timeoutMillis) throws InterruptedException {
        String normalizedAdapterMailboxKey = normalizePollingPendingDeliveryQueueKey(adapterMailboxKey);
        String normalizedWorkerId = TransportDeliveryAddressing.normalizeText(authenticatedWorkerId);
        if (normalizedAdapterMailboxKey == null || normalizedWorkerId == null || maxItems <= 0) {
            return PollingPendingDeliveryPollResult.invalidRequest();
        }
        if (!running.get()) {
            return PollingPendingDeliveryPollResult.shutdown();
        }
        if (timeoutMillis <= 0) {
            List<DispatchRoutingItem> drained = drain(normalizedAdapterMailboxKey, normalizedWorkerId, maxItems);
            if (!running.get()) {
                return PollingPendingDeliveryPollResult.shutdown();
            }
            return drained.isEmpty()
                    ? PollingPendingDeliveryPollResult.empty()
                    : PollingPendingDeliveryPollResult.deliveredView(drained);
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (running.get()) {
            List<DispatchRoutingItem> drained = drain(normalizedAdapterMailboxKey, normalizedWorkerId, maxItems);
            if (!drained.isEmpty()) {
                return PollingPendingDeliveryPollResult.deliveredView(drained);
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return running.get()
                        ? PollingPendingDeliveryPollResult.empty()
                        : PollingPendingDeliveryPollResult.shutdown();
            }
            Thread.sleep(Math.min(10L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        }
        return PollingPendingDeliveryPollResult.shutdown();
    }

    public PollingPendingDeliveryBufferStats stats() {
        try {
            Map<String, String> globalStats = commands.hgetall(namespace.globalStatsKey());
            Map<String, QueueStatsAccumulator> queueByMailbox = new LinkedHashMap<>();
            int computedQueuedItems = 0;
            long oldestCreatedAt = Long.MAX_VALUE;

            for (String encodedKeyPart : commands.smembers(namespace.activeQueuesKey())) {
                long queuedForSlot = commands.llen(namespace.queueKey(encodedKeyPart));
                if (queuedForSlot <= 0) {
                    commands.srem(namespace.activeQueuesKey(), encodedKeyPart);
                    commands.del(namespace.metaKey(encodedKeyPart));
                    continue;
                }
                String slotKey = codec.decodeKeyPart(encodedKeyPart).queueKey();
                String mailboxKey = mailboxFromSlotKey(slotKey);
                Map<String, String> meta = commands.hgetall(namespace.metaKey(encodedKeyPart));
                long oldestForSlot = parseLong(meta.get("oldestCreatedAtEpochMillis"));
                long backpressureForSlot = parseLong(meta.get("backpressureRejectedItems"));

                if (oldestForSlot > 0) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, oldestForSlot);
                }
                computedQueuedItems += Math.toIntExact(queuedForSlot);
                queueByMailbox
                        .computeIfAbsent(mailboxKey, ignored -> new QueueStatsAccumulator())
                        .add(Math.toIntExact(queuedForSlot), oldestForSlot, backpressureForSlot);
            }

            Map<String, PollingPendingDeliveryQueueStats> queueStats = new LinkedHashMap<>();
            queueByMailbox.forEach((mailbox, accumulator) -> queueStats.put(mailbox, accumulator.toStats()));

            long globalQueuedItems = parseLong(globalStats.get("queuedItems"));
            long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                    ? 0L
                    : Math.max(0L, System.currentTimeMillis() - oldestCreatedAt);
            return new PollingPendingDeliveryBufferStats(
                    globalQueuedItems > 0 ? Math.toIntExact(globalQueuedItems) : computedQueuedItems,
                    queueStats.size(),
                    0,
                    maxQueuedItems,
                    oldestQueuedAgeMillis,
                    parseLong(globalStats.get("enqueuedItems")),
                    parseLong(globalStats.get("drainedItems")),
                    parseLong(globalStats.get("backpressureRejectedItems")),
                    parseLong(globalStats.get("invalidItems")) + localInvalidItems.get(),
                    parseLong(globalStats.get("unavailableItems")) + localUnavailableItems.get(),
                    parseLong(globalStats.get("shutdownClearedItems")) + localShutdownClearedItems.get(),
                    queueStats
            );
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            return new PollingPendingDeliveryBufferStats(
                    0,
                    0,
                    0,
                    maxQueuedItems,
                    0L,
                    0L,
                    0L,
                    0L,
                    localInvalidItems.get(),
                    localUnavailableItems.get(),
                    localShutdownClearedItems.get()
            );
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        PollingPendingDeliveryBufferStats beforeClose = stats();
        try {
            clearNamespace();
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
        }
        localShutdownClearedItems.addAndGet(beforeClose.getQueuedItems());
        if (closed.compareAndSet(false, true)) {
            if (connection.isOpen()) {
                connection.close();
            }
            if (ownsClient && redisClient != null) {
                redisClient.shutdown();
            }
        }
    }

    private DispatchOutcome enqueueOne(String adapterMailboxKey, DispatchRoutingItem item) {
        String normalizedAdapterMailboxKey = normalizePollingPendingDeliveryQueueKey(adapterMailboxKey);
        String normalizedSelectedWorkerId = item == null
                ? null
                : TransportDeliveryAddressing.normalizeText(item.selectedWorkerId());
        if (item == null || normalizedAdapterMailboxKey == null) {
            localInvalidItems.incrementAndGet();
            return DispatchOutcome.invalid(
                    item != null ? item.deliveryId() : null,
                    normalizedSelectedWorkerId,
                    item != null ? item.correlationRef() : null,
                    "adapterMailboxKey must not be blank"
            );
        }
        if (normalizedSelectedWorkerId == null) {
            localInvalidItems.incrementAndGet();
            return DispatchOutcome.invalid(
                    item.deliveryId(),
                    null,
                    item.correlationRef(),
                    "selectedWorkerId must not be blank"
            );
        }
        if (!running.get()) {
            localUnavailableItems.incrementAndGet();
            return new DispatchOutcome(
                    item.deliveryId(),
                    normalizedSelectedWorkerId,
                    item.correlationRef(),
                    DispatchOutcomeStatus.UNAVAILABLE,
                    true,
                    "polling pending delivery buffer is stopped",
                    System.currentTimeMillis()
            );
        }

        DispatchRoutingItem normalizedItem = normalizeItem(item, normalizedSelectedWorkerId);
        String encodedKeyPart = codec.encodeKeyPart(new PollingPendingDeliveryQueueKey(
                slotKey(normalizedAdapterMailboxKey, normalizedSelectedWorkerId)
        ));
        try {
            Object rawResponse = commands.eval(
                    OFFER_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            namespace.queueKey(encodedKeyPart),
                            namespace.metaKey(encodedKeyPart),
                            namespace.activeQueuesKey(),
                            namespace.globalStatsKey()
                    },
                    codec.encodeStoredValue(new KeyedQueueEntry<>(normalizedItem, normalizedItem.createdAtEpochMillis())),
                    String.valueOf(normalizedItem.createdAtEpochMillis()),
                    String.valueOf(maxItemsPerWorker),
                    String.valueOf(maxQueuedItems),
                    encodedKeyPart
            );
            return mapOfferResponse(rawResponse, normalizedItem);
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            return new DispatchOutcome(
                    normalizedItem.deliveryId(),
                    normalizedItem.selectedWorkerId(),
                    normalizedItem.correlationRef(),
                    DispatchOutcomeStatus.UNAVAILABLE,
                    true,
                    "redis polling pending delivery buffer is unavailable: " + ex.getMessage(),
                    System.currentTimeMillis()
            );
        }
    }

    private List<DispatchRoutingItem> drain(String adapterMailboxKey, String selectedWorkerId, int maxItems) {
        if (!running.get()) {
            return List.of();
        }
        String encodedKeyPart = codec.encodeKeyPart(new PollingPendingDeliveryQueueKey(
                slotKey(adapterMailboxKey, selectedWorkerId)
        ));
        try {
            Object rawResponse = commands.eval(
                    DRAIN_SLOT_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            namespace.queueKey(encodedKeyPart),
                            namespace.metaKey(encodedKeyPart),
                            namespace.activeQueuesKey(),
                            namespace.globalStatsKey()
                    },
                    String.valueOf(maxItems),
                    encodedKeyPart
            );
            return mapDrainResponse(rawResponse);
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            return List.of();
        }
    }

    private DispatchOutcome mapOfferResponse(Object rawResponse, DispatchRoutingItem item) {
        if (!(rawResponse instanceof List<?> values) || values.isEmpty()) {
            return unavailable(item, "polling pending delivery buffer returned no response");
        }
        String code = stringValue(values.getFirst());
        String reason = values.size() > 1 ? stringValue(values.get(1)) : null;
        return switch (code) {
            case "ENQUEUED" -> DispatchOutcome.queued(item.deliveryId(), item.selectedWorkerId(), item.correlationRef());
            case "BACKPRESSURE_SLOT", "BACKPRESSURE_GLOBAL" -> DispatchOutcome.backpressure(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.correlationRef(),
                    reason == null ? "polling pending delivery buffer is full" : reason
            );
            case "INVALID" -> DispatchOutcome.invalid(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.correlationRef(),
                    reason == null ? "adapterMailboxKey must not be blank" : reason
            );
            default -> unavailable(item, reason == null
                    ? "polling pending delivery buffer returned unsupported response: " + code
                    : reason);
        };
    }

    private List<DispatchRoutingItem> mapDrainResponse(Object rawResponse) {
        if (!(rawResponse instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        String code = stringValue(values.getFirst());
        if (!Objects.equals(code, "DRAINED") || values.size() <= 2) {
            return List.of();
        }
        List<DispatchRoutingItem> drained = new ArrayList<>(values.size() - 2);
        for (Object rawValue : values.subList(2, values.size())) {
            if (rawValue != null) {
                drained.add(codec.decodeStoredValue(rawValue.toString()).value());
            }
        }
        return List.copyOf(drained);
    }

    private DispatchOutcome unavailable(DispatchRoutingItem item, String reason) {
        localUnavailableItems.incrementAndGet();
        return new DispatchOutcome(
                item.deliveryId(),
                item.selectedWorkerId(),
                item.correlationRef(),
                DispatchOutcomeStatus.UNAVAILABLE,
                true,
                reason,
                System.currentTimeMillis()
        );
    }

    private static DispatchRoutingItem normalizeItem(DispatchRoutingItem item, String normalizedSelectedWorkerId) {
        if (Objects.equals(normalizedSelectedWorkerId, item.selectedWorkerId())) {
            return item;
        }
        return new DispatchRoutingItem(
                item.deliveryId(),
                normalizedSelectedWorkerId,
                item.payload(),
                item.correlationRef(),
                item.deadlineEpochMillis(),
                item.createdAtEpochMillis()
        );
    }

    private static String slotKey(String adapterMailboxKey, String selectedWorkerId) {
        return adapterMailboxKey + SLOT_DELIMITER + selectedWorkerId;
    }

    private static String mailboxFromSlotKey(String slotKey) {
        int delimiter = slotKey.indexOf(SLOT_DELIMITER);
        return delimiter < 0 ? slotKey : slotKey.substring(0, delimiter);
    }

    private static String normalizePollingPendingDeliveryQueueKey(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text.trim();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }

    private void clearNamespace() {
        List<String> queueKeys = commands.smembers(namespace.activeQueuesKey()).stream()
                .flatMap(encodedKeyPart -> List.of(
                        namespace.queueKey(encodedKeyPart),
                        namespace.metaKey(encodedKeyPart)
                ).stream())
                .toList();
        if (!queueKeys.isEmpty()) {
            commands.del(queueKeys.toArray(String[]::new));
        }
        commands.del(namespace.activeQueuesKey(), namespace.globalStatsKey());
    }

    private static final class QueueStatsAccumulator {
        private int queuedItems;
        private int slotCount;
        private long oldestCreatedAtEpochMillis;
        private long backpressureRejectedItems;

        private void add(int queuedItems, long oldestCreatedAtEpochMillis, long backpressureRejectedItems) {
            this.queuedItems += queuedItems;
            this.slotCount++;
            if (oldestCreatedAtEpochMillis > 0) {
                this.oldestCreatedAtEpochMillis = this.oldestCreatedAtEpochMillis == 0
                        ? oldestCreatedAtEpochMillis
                        : Math.min(this.oldestCreatedAtEpochMillis, oldestCreatedAtEpochMillis);
            }
            this.backpressureRejectedItems += Math.max(0L, backpressureRejectedItems);
        }

        private PollingPendingDeliveryQueueStats toStats() {
            return new PollingPendingDeliveryQueueStats(
                    queuedItems,
                    slotCount,
                    0,
                    oldestCreatedAtEpochMillis <= 0
                            ? 0L
                            : Math.max(0L, System.currentTimeMillis() - oldestCreatedAtEpochMillis),
                    backpressureRejectedItems
            );
        }
    }
}
