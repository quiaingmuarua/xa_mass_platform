package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueNamespace;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
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
 * Redis-backed transport delivery store.
 *
 * <p>The physical Redis queue key is bucket-scoped. {@code selectedWorkerId}
 * stays on each queued value and is used only as a demux constraint during
 * drain/poll.
 */
public final class RedisTransportDeliveryStore implements TransportDeliveryStore {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS;
    public static final int DEFAULT_MAX_ITEMS_PER_ROUTE = InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE;
    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DELIVERY;
    private static final String DRAIN_BY_WORKER_SCRIPT = """
            local queueKey = KEYS[1]
            local metaKey = KEYS[2]
            local activeQueuesKey = KEYS[3]
            local globalStatsKey = KEYS[4]
            local maxItems = tonumber(ARGV[1])
            local encodedKeyPart = ARGV[2]
            local encodedSelectedWorkerId = ARGV[3]
            local tombstone = ARGV[4]

            if (not queueKey) or (not maxItems) or (not encodedKeyPart) or
               (not encodedSelectedWorkerId) or (not tombstone) then
                return {'INVALID', 'key, selected worker, and maxItems must not be null'}
            end

            if maxItems <= 0 then
                return {'INVALID', 'maxItems must be positive'}
            end

            local values = redis.call('LRANGE', queueKey, 0, -1)
            local drained = {}
            local count = 0
            for index, value in ipairs(values) do
                if count >= maxItems then
                    break
                end
                local first = string.find(value, '|', 1, true)
                local second = nil
                if first then
                    second = string.find(value, '|', first + 1, true)
                end
                if first and second then
                    local selected = string.sub(value, first + 1, second - 1)
                    if selected == encodedSelectedWorkerId then
                        count = count + 1
                        drained[count] = value
                        redis.call('LSET', queueKey, index - 1, tombstone)
                    end
                end
            end

            if count == 0 then
                return {'EMPTY', '0'}
            end

            redis.call('LREM', queueKey, 0, tombstone)
            redis.call('HINCRBY', globalStatsKey, 'queuedItems', -count)
            redis.call('HINCRBY', globalStatsKey, 'drainedItems', count)

            local nextHead = redis.call('LINDEX', queueKey, 0)
            if nextHead then
                local delimiter = string.find(nextHead, '|', 1, true)
                if delimiter and delimiter > 1 then
                    local nextCreatedAt = string.sub(nextHead, 1, delimiter - 1)
                    redis.call('HSET', metaKey, 'oldestCreatedAtEpochMillis', nextCreatedAt)
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

    private static final String OFFER_SCRIPT = """
            local queueKey = KEYS[1]
            local metaKey = KEYS[2]
            local activeQueuesKey = KEYS[3]
            local globalStatsKey = KEYS[4]
            local queueValue = ARGV[1]
            local createdAtEpochMillis = ARGV[2]
            local maxItemsPerKey = tonumber(ARGV[3])
            local maxQueuedItems = tonumber(ARGV[4])
            local encodedKeyPart = ARGV[5]

            if (not queueKey) or (not queueValue) or (not createdAtEpochMillis) or
               (not maxItemsPerKey) or (not maxQueuedItems) or (not encodedKeyPart) then
                redis.call('HINCRBY', globalStatsKey, 'invalidItems', 1)
                return {'INVALID', 'key and entry must not be null'}
            end

            if maxItemsPerKey <= 0 then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_KEY', 'queue capacity is exhausted'}
            end

            local queueLength = redis.call('LLEN', queueKey)
            if queueLength >= maxItemsPerKey then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_KEY', 'queue is full'}
            end

            local queuedItems = tonumber(redis.call('HGET', globalStatsKey, 'queuedItems') or '0')
            if queuedItems >= maxQueuedItems then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_GLOBAL', 'runtime backlog is full'}
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

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisKeyedQueueNamespace namespace;
    private final RedisQueuedPulledDispatchCodec codec = new RedisQueuedPulledDispatchCodec();
    private final boolean ownsClient;
    private final int maxQueuedItems;
    private final int maxItemsPerRoute;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong localInvalidItems = new AtomicLong();
    private final AtomicLong localUnavailableItems = new AtomicLong();
    private final AtomicLong localShutdownClearedItems = new AtomicLong();

    public RedisTransportDeliveryStore(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_ITEMS, DEFAULT_MAX_ITEMS_PER_ROUTE);
    }

    public RedisTransportDeliveryStore(String redisUri,
                                       String namespacePrefix,
                                       int maxQueuedItems,
                                       int maxItemsPerRoute) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedItems,
                maxItemsPerRoute,
                true
        );
    }

    RedisTransportDeliveryStore(RedisClient redisClient,
                                String namespacePrefix,
                                int maxQueuedItems,
                                int maxItemsPerRoute,
                                boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                maxQueuedItems,
                maxItemsPerRoute,
                ownsClient
        );
    }

    RedisTransportDeliveryStore(StatefulRedisConnection<String, String> connection,
                                String namespacePrefix,
                                int maxQueuedItems,
                                int maxItemsPerRoute) {
        this(null, connection, namespacePrefix, maxQueuedItems, maxItemsPerRoute, false);
    }

    private RedisTransportDeliveryStore(RedisClient redisClient,
                                        StatefulRedisConnection<String, String> connection,
                                        String namespacePrefix,
                                        int maxQueuedItems,
                                        int maxItemsPerRoute,
                                        boolean ownsClient) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        if (maxItemsPerRoute <= 0) {
            throw new IllegalArgumentException("maxItemsPerRoute must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespace = new RedisKeyedQueueNamespace(namespacePrefix);
        this.maxQueuedItems = maxQueuedItems;
        this.maxItemsPerRoute = maxItemsPerRoute;
        this.ownsClient = ownsClient;
    }

    @Override
    public DispatchOutcome enqueue(String adapterMailboxKey, QueuedPulledDispatch item) {
        String normalizedAdapterMailboxKey = normalizeDeliveryQueueKey(adapterMailboxKey);
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
                    "delivery store is stopped",
                    System.currentTimeMillis()
            );
        }

        QueuedPulledDispatch normalizedItem = normalizeItem(item, normalizedSelectedWorkerId);
        String encodedKeyPart = codec.encodeKeyPart(new DeliveryQueueKey(normalizedAdapterMailboxKey));
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
                    String.valueOf(maxItemsPerRoute),
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
                    "redis delivery queue is unavailable: " + ex.getMessage(),
                    System.currentTimeMillis()
            );
        }
    }

    @Override
    public List<QueuedPulledDispatch> drain(String adapterMailboxKey, String selectedWorkerId, int maxItems) {
        String normalizedAdapterMailboxKey = normalizeDeliveryQueueKey(adapterMailboxKey);
        String normalizedSelectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        if (normalizedAdapterMailboxKey == null || normalizedSelectedWorkerId == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        String encodedKeyPart = codec.encodeKeyPart(new DeliveryQueueKey(normalizedAdapterMailboxKey));
        try {
            Object rawResponse = commands.eval(
                    DRAIN_BY_WORKER_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            namespace.queueKey(encodedKeyPart),
                            namespace.metaKey(encodedKeyPart),
                            namespace.activeQueuesKey(),
                            namespace.globalStatsKey()
                    },
                    String.valueOf(maxItems),
                    encodedKeyPart,
                    codec.encodeSelectedWorkerToken(normalizedSelectedWorkerId),
                    "__xa_mass_transport_drained__"
            );
            return mapDrainResponse(rawResponse);
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            return List.of();
        }
    }

    @Override
    public TransportDeliveryPollResult poll(String adapterMailboxKey,
                                            String selectedWorkerId,
                                            int maxItems,
                                            long timeout,
                                            TimeUnit unit) throws InterruptedException {
        String normalizedAdapterMailboxKey = normalizeDeliveryQueueKey(adapterMailboxKey);
        String normalizedSelectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        if (normalizedAdapterMailboxKey == null || normalizedSelectedWorkerId == null || maxItems <= 0) {
            return TransportDeliveryPollResult.invalidRequest();
        }
        if (!running.get()) {
            return TransportDeliveryPollResult.shutdown();
        }
        if (timeout <= 0) {
            List<QueuedPulledDispatch> drained = drain(normalizedAdapterMailboxKey, normalizedSelectedWorkerId, maxItems);
            if (!running.get()) {
                return TransportDeliveryPollResult.shutdown();
            }
            return drained.isEmpty() ? TransportDeliveryPollResult.empty() : TransportDeliveryPollResult.deliveredView(drained);
        }

        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long sleepMillis = 10L;
        while (running.get()) {
            List<QueuedPulledDispatch> drained = drain(normalizedAdapterMailboxKey, normalizedSelectedWorkerId, maxItems);
            if (!drained.isEmpty()) {
                return TransportDeliveryPollResult.deliveredView(drained);
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return running.get() ? TransportDeliveryPollResult.empty() : TransportDeliveryPollResult.shutdown();
            }
            Thread.sleep(Math.min(sleepMillis, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        }
        return TransportDeliveryPollResult.shutdown();
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        try {
            Map<String, String> globalStats = commands.hgetall(namespace.globalStatsKey());
            Map<String, TransportDeliveryQueueStats> queueByAdapter = new LinkedHashMap<>();
            int computedQueueCount = 0;
            int computedQueuedItems = 0;
            long oldestCreatedAt = Long.MAX_VALUE;
            long backpressureRejectedItems = parseLong(globalStats.get("backpressureRejectedItems"));
            for (String encodedKeyPart : commands.smembers(namespace.activeQueuesKey())) {
                long queuedForKey = commands.llen(namespace.queueKey(encodedKeyPart));
                if (queuedForKey <= 0) {
                    commands.srem(namespace.activeQueuesKey(), encodedKeyPart);
                    commands.del(namespace.metaKey(encodedKeyPart));
                    continue;
                }
                DeliveryQueueKey deliveryQueueKey = codec.decodeKeyPart(encodedKeyPart);
                Map<String, String> meta = commands.hgetall(namespace.metaKey(encodedKeyPart));
                long oldestCreatedAtEpochMillis = parseLong(meta.get("oldestCreatedAtEpochMillis"));
                if (oldestCreatedAtEpochMillis > 0) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, oldestCreatedAtEpochMillis);
                }
                computedQueueCount++;
                computedQueuedItems += Math.toIntExact(queuedForKey);
                long queueBackpressure = parseLong(meta.get("backpressureRejectedItems"));
                queueByAdapter.put(deliveryQueueKey.deliveryQueueKey(), new TransportDeliveryQueueStats(
                        Math.toIntExact(queuedForKey),
                        1,
                        0,
                        oldestCreatedAtEpochMillis <= 0
                                ? 0L
                                : Math.max(0L, System.currentTimeMillis() - oldestCreatedAtEpochMillis),
                        queueBackpressure
                ));
            }
            long globalQueuedItems = parseLong(globalStats.get("queuedItems"));
            long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                    ? 0L
                    : Math.max(0L, System.currentTimeMillis() - oldestCreatedAt);
            return new TransportDeliveryStoreStats(
                    globalQueuedItems > 0 ? Math.toIntExact(globalQueuedItems) : computedQueuedItems,
                    computedQueueCount,
                    0,
                    maxQueuedItems,
                    oldestQueuedAgeMillis,
                    parseLong(globalStats.get("enqueuedItems")),
                    parseLong(globalStats.get("drainedItems")),
                    backpressureRejectedItems,
                    parseLong(globalStats.get("invalidItems")) + localInvalidItems.get(),
                    parseLong(globalStats.get("unavailableItems")) + localUnavailableItems.get(),
                    parseLong(globalStats.get("shutdownClearedItems")) + localShutdownClearedItems.get(),
                    queueByAdapter
            );
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            return new TransportDeliveryStoreStats(
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
        TransportDeliveryStoreStats beforeClose = stats();
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

    private DispatchOutcome mapOfferResponse(Object rawResponse, QueuedPulledDispatch item) {
        if (!(rawResponse instanceof List<?> values) || values.isEmpty()) {
            return unavailable(item, "delivery queue returned no response");
        }
        String code = stringValue(values.getFirst());
        String reason = values.size() > 1 ? stringValue(values.get(1)) : null;
        return switch (code) {
            case "ENQUEUED" -> DispatchOutcome.queued(item.deliveryId(), item.selectedWorkerId(), item.correlationRef());
            case "BACKPRESSURE_KEY" -> DispatchOutcome.backpressure(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.correlationRef(),
                    reason == null ? "delivery queue is full" : resolveBackpressureReason(reason)
            );
            case "BACKPRESSURE_GLOBAL" -> DispatchOutcome.backpressure(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.correlationRef(),
                    reason == null ? "runtime delivery backlog is full" : resolveBackpressureReason(reason)
            );
            case "INVALID" -> DispatchOutcome.invalid(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.correlationRef(),
                    reason == null ? "adapterMailboxKey must not be blank" : reason
            );
            default -> unavailable(item, reason == null ? "delivery queue returned unsupported response: " + code : reason);
        };
    }

    private List<QueuedPulledDispatch> mapDrainResponse(Object rawResponse) {
        if (!(rawResponse instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        String code = stringValue(values.getFirst());
        if (!Objects.equals(code, "DRAINED") || values.size() <= 2) {
            return List.of();
        }
        List<QueuedPulledDispatch> drained = new ArrayList<>(values.size() - 2);
        for (Object rawValue : values.subList(2, values.size())) {
            if (rawValue == null) {
                continue;
            }
            drained.add(codec.decodeStoredValue(rawValue.toString()).value());
        }
        return List.copyOf(drained);
    }

    private DispatchOutcome unavailable(QueuedPulledDispatch item, String reason) {
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

    private static QueuedPulledDispatch normalizeItem(QueuedPulledDispatch item, String normalizedSelectedWorkerId) {
        if (Objects.equals(normalizedSelectedWorkerId, item.selectedWorkerId())) {
            return item;
        }
        return new QueuedPulledDispatch(
                item.deliveryId(),
                normalizedSelectedWorkerId,
                item.payload(),
                item.correlationRef(),
                item.createdAtEpochMillis()
        );
    }

    private static String resolveBackpressureReason(String primitiveReason) {
        return switch (primitiveReason == null ? "" : primitiveReason) {
            case "runtime backlog is full" -> "runtime delivery backlog is full";
            case "queue is full", "queue capacity is exhausted" -> "delivery queue is full";
            default -> "delivery queue is full";
        };
    }

    private static String normalizeDeliveryQueueKey(String value) {
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
}
