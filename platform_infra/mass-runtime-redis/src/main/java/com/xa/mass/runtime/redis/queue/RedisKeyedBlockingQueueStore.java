package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueKeySnapshot;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.queue.KeyedQueueSnapshot;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed keyed blocking queue store.
 */
public final class RedisKeyedBlockingQueueStore<K, V> implements KeyedBlockingQueueStore<K, V> {

    private static final char STORED_VALUE_DELIMITER = '|';

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisKeyedQueueNamespace namespace;
    private final RedisKeyedQueueCodec<K, V> codec;
    private final RedisKeyedQueueOptions options;
    private final RedisKeyedQueueScripts scripts;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong localUnavailableItems = new AtomicLong();
    private final AtomicLong localShutdownClearedItems = new AtomicLong();
    private volatile KeyedQueueSnapshot<K> terminalSnapshot;
    private volatile KeyedQueueSnapshot<K> cachedSnapshot;
    private volatile long cachedSnapshotAtEpochMillis;

    public RedisKeyedBlockingQueueStore(String redisUri,
                                        RedisKeyedQueueNamespace namespace,
                                        RedisKeyedQueueCodec<K, V> codec,
                                        RedisKeyedQueueOptions options) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespace,
                codec,
                options,
                new RedisKeyedQueueScripts(),
                true);
    }

    public RedisKeyedBlockingQueueStore(RedisClient redisClient,
                                        RedisKeyedQueueNamespace namespace,
                                        RedisKeyedQueueCodec<K, V> codec,
                                        RedisKeyedQueueOptions options,
                                        RedisKeyedQueueScripts scripts,
                                        boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespace,
                codec,
                options,
                scripts,
                ownsClient);
    }

    public RedisKeyedBlockingQueueStore(StatefulRedisConnection<String, String> connection,
                                        RedisKeyedQueueNamespace namespace,
                                        RedisKeyedQueueCodec<K, V> codec,
                                        RedisKeyedQueueOptions options,
                                        RedisKeyedQueueScripts scripts) {
        this(null, connection, namespace, codec, options, scripts, false);
    }

    private RedisKeyedBlockingQueueStore(RedisClient redisClient,
                                         StatefulRedisConnection<String, String> connection,
                                         RedisKeyedQueueNamespace namespace,
                                         RedisKeyedQueueCodec<K, V> codec,
                                         RedisKeyedQueueOptions options,
                                         RedisKeyedQueueScripts scripts,
                                         boolean ownsClient) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.options = Objects.requireNonNull(options, "options");
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        this.ownsClient = ownsClient;
    }

    @Override
    public KeyedQueueOfferResult offer(K key, KeyedQueueEntry<V> entry, int maxItemsPerKey) {
        if (key == null || entry == null) {
            return KeyedQueueOfferResult.invalid("key and entry must not be null");
        }
        if (maxItemsPerKey <= 0) {
            return KeyedQueueOfferResult.backpressureRejected("queue capacity is exhausted");
        }
        if (!running.get()) {
            return KeyedQueueOfferResult.unavailable("queue store is stopped");
        }

        String encodedKeyPart = codec.encodeKeyPart(key);
        String queueKey = namespace.queueKey(encodedKeyPart);
        String metaKey = namespace.metaKey(encodedKeyPart);
        String activeQueuesKey = namespace.activeQueuesKey();
        String globalStatsKey = namespace.globalStatsKey();
        String encodedValue = Base64.getEncoder().encodeToString(codec.encodeValue(entry));
        String storedValue = wrapStoredValue(entry.createdAtEpochMillis(), encodedValue);

        try {
            Object rawResponse = commands.eval(
                    scripts.offerScript(),
                    ScriptOutputType.MULTI,
                    new String[]{queueKey, metaKey, activeQueuesKey, globalStatsKey},
                    storedValue,
                    String.valueOf(entry.createdAtEpochMillis()),
                    String.valueOf(maxItemsPerKey),
                    String.valueOf(options.maxQueuedItems()),
                    encodedKeyPart
            );
            invalidateSnapshot();
            return mapOfferResponse(rawResponse);
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            invalidateSnapshot();
            return KeyedQueueOfferResult.unavailable("redis queue is unavailable: " + ex.getMessage());
        }
    }

    @Override
    public List<KeyedQueueEntry<V>> drain(K key, int maxItems) {
        if (key == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        String encodedKeyPart = codec.encodeKeyPart(key);
        try {
            Object rawResponse = commands.eval(
                    scripts.drainScript(),
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
            List<KeyedQueueEntry<V>> drained = mapDrainResponse(rawResponse);
            if (!drained.isEmpty()) {
                invalidateSnapshot();
            }
            return drained;
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            invalidateSnapshot();
            return List.of();
        }
    }

    @Override
    public KeyedQueuePollResult<V> poll(K key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException {
        if (key == null || maxItems <= 0) {
            return KeyedQueuePollResult.invalid();
        }
        if (!running.get()) {
            return KeyedQueuePollResult.shutdown();
        }
        if (timeout <= 0) {
            List<KeyedQueueEntry<V>> drained = drain(key, maxItems);
            if (!running.get()) {
                return KeyedQueuePollResult.shutdown();
            }
            return drained.isEmpty() ? KeyedQueuePollResult.empty() : KeyedQueuePollResult.delivered(drained);
        }

        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long sleepMillis = Math.max(1L, options.pollSleepInterval().toMillis());
        while (running.get()) {
            List<KeyedQueueEntry<V>> drained = drain(key, maxItems);
            if (!drained.isEmpty()) {
                return KeyedQueuePollResult.delivered(drained);
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return running.get() ? KeyedQueuePollResult.empty() : KeyedQueuePollResult.shutdown();
            }
            Thread.sleep(Math.min(sleepMillis, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        }
        return KeyedQueuePollResult.shutdown();
    }

    @Override
    public KeyedQueueSnapshot<K> snapshot() {
        KeyedQueueSnapshot<K> terminal = terminalSnapshot;
        if (terminal != null) {
            return terminal;
        }
        long nowMillis = System.currentTimeMillis();
        KeyedQueueSnapshot<K> snapshot = cachedSnapshot;
        if (snapshot != null && nowMillis - cachedSnapshotAtEpochMillis <= options.snapshotCacheWindow().toMillis()) {
            return snapshot;
        }
        synchronized (this) {
            terminal = terminalSnapshot;
            if (terminal != null) {
                return terminal;
            }
            snapshot = cachedSnapshot;
            nowMillis = System.currentTimeMillis();
            if (snapshot != null && nowMillis - cachedSnapshotAtEpochMillis <= options.snapshotCacheWindow().toMillis()) {
                return snapshot;
            }
            KeyedQueueSnapshot<K> refreshed = refreshSnapshot(nowMillis);
            cachedSnapshot = refreshed;
            cachedSnapshotAtEpochMillis = nowMillis;
            return refreshed;
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        KeyedQueueSnapshot<K> beforeClose = snapshot();
        int clearedQueuedItems = beforeClose.queuedItems();
        try {
            clearNamespace();
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
        }
        localShutdownClearedItems.addAndGet(clearedQueuedItems);
        terminalSnapshot = new KeyedQueueSnapshot<>(
                0,
                0,
                0,
                options.maxQueuedItems(),
                0L,
                beforeClose.enqueuedItems(),
                beforeClose.drainedItems(),
                beforeClose.backpressureRejectedItems(),
                beforeClose.invalidItems(),
                beforeClose.unavailableItems(),
                beforeClose.shutdownClearedItems() + clearedQueuedItems,
                Map.of()
        );
        if (closed.compareAndSet(false, true)) {
            if (connection.isOpen()) {
                connection.close();
            }
            if (ownsClient && redisClient != null) {
                redisClient.shutdown();
            }
        }
    }

    private KeyedQueueSnapshot<K> refreshSnapshot(long nowMillis) {
        try {
            Map<String, String> globalStats = commands.hgetall(namespace.globalStatsKey());
            Map<K, KeyedQueueKeySnapshot> queueByKey = new LinkedHashMap<>();
            int computedQueueCount = 0;
            int computedQueuedItems = 0;
            long oldestCreatedAt = Long.MAX_VALUE;
            for (String encodedKeyPart : commands.smembers(namespace.activeQueuesKey())) {
                long queuedForKey = commands.llen(namespace.queueKey(encodedKeyPart));
                if (queuedForKey <= 0) {
                    commands.srem(namespace.activeQueuesKey(), encodedKeyPart);
                    commands.del(namespace.metaKey(encodedKeyPart));
                    continue;
                }
                Map<String, String> meta = commands.hgetall(namespace.metaKey(encodedKeyPart));
                long oldestCreatedAtEpochMillis = parseLong(meta.get("oldestCreatedAtEpochMillis"));
                oldestCreatedAt = oldestCreatedAtEpochMillis > 0
                        ? Math.min(oldestCreatedAt, oldestCreatedAtEpochMillis)
                        : oldestCreatedAt;
                computedQueueCount++;
                computedQueuedItems += Math.toIntExact(queuedForKey);
                queueByKey.put(codec.decodeKeyPart(encodedKeyPart), new KeyedQueueKeySnapshot(
                        Math.toIntExact(queuedForKey),
                        0,
                        oldestCreatedAtEpochMillis <= 0 ? 0L : Math.max(0L, nowMillis - oldestCreatedAtEpochMillis),
                        parseLong(meta.get("backpressureRejectedItems"))
                ));
            }
            long globalQueuedItems = parseLong(globalStats.get("queuedItems"));
            long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                    ? 0L
                    : Math.max(0L, nowMillis - oldestCreatedAt);
            return new KeyedQueueSnapshot<>(
                    globalQueuedItems > 0 ? Math.toIntExact(globalQueuedItems) : computedQueuedItems,
                    computedQueueCount,
                    0,
                    options.maxQueuedItems(),
                    oldestQueuedAgeMillis,
                    parseLong(globalStats.get("enqueuedItems")),
                    parseLong(globalStats.get("drainedItems")),
                    parseLong(globalStats.get("backpressureRejectedItems")),
                    parseLong(globalStats.get("invalidItems")),
                    parseLong(globalStats.get("unavailableItems")) + localUnavailableItems.get(),
                    parseLong(globalStats.get("shutdownClearedItems")) + localShutdownClearedItems.get(),
                    queueByKey
            );
        } catch (RuntimeException ex) {
            localUnavailableItems.incrementAndGet();
            return new KeyedQueueSnapshot<>(
                    0,
                    0,
                    0,
                    options.maxQueuedItems(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    localUnavailableItems.get(),
                    localShutdownClearedItems.get(),
                    Map.of()
            );
        }
    }

    static KeyedQueueOfferResult mapOfferResponse(Object rawResponse) {
        if (!(rawResponse instanceof List<?> values) || values.isEmpty()) {
            return KeyedQueueOfferResult.unavailable("queue store returned no response");
        }
        String code = stringValue(values.getFirst());
        String reason = values.size() > 1 ? stringValue(values.get(1)) : null;
        return switch (code) {
            case "ENQUEUED" -> KeyedQueueOfferResult.enqueued();
            case "BACKPRESSURE_KEY" -> KeyedQueueOfferResult.backpressureRejected(
                    reason == null ? "queue is full" : reason
            );
            case "BACKPRESSURE_GLOBAL" -> KeyedQueueOfferResult.backpressureRejected(
                    reason == null ? "runtime backlog is full" : reason
            );
            case "INVALID" -> KeyedQueueOfferResult.invalid(
                    reason == null ? "key and entry must not be null" : reason
            );
            case "UNAVAILABLE" -> KeyedQueueOfferResult.unavailable(
                    reason == null ? "queue store is stopped" : reason
            );
            default -> KeyedQueueOfferResult.unavailable(
                    reason == null ? "queue store returned unsupported response: " + code : reason
            );
        };
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text.trim();
    }

    private List<KeyedQueueEntry<V>> mapDrainResponse(Object rawResponse) {
        if (!(rawResponse instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        String code = stringValue(values.getFirst());
        if (!Objects.equals(code, "DRAINED")) {
            return List.of();
        }
        if (values.size() <= 2) {
            return List.of();
        }
        return values.subList(2, values.size()).stream()
                .map(this::decodeStoredEntry)
                .toList();
    }

    private KeyedQueueEntry<V> decodeStoredEntry(Object rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("queue value must not be null");
        }
        String storedValue = rawValue.toString();
        int delimiterIndex = storedValue.indexOf(STORED_VALUE_DELIMITER);
        if (delimiterIndex <= 0 || delimiterIndex == storedValue.length() - 1) {
            throw new IllegalArgumentException("stored queue value is malformed");
        }
        String encodedValue = storedValue.substring(delimiterIndex + 1);
        return codec.decodeValue(Base64.getDecoder().decode(encodedValue));
    }

    private static String wrapStoredValue(long createdAtEpochMillis, String encodedValue) {
        return createdAtEpochMillis + String.valueOf(STORED_VALUE_DELIMITER) + encodedValue;
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }

    private void invalidateSnapshot() {
        cachedSnapshot = null;
        cachedSnapshotAtEpochMillis = 0L;
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
        invalidateSnapshot();
    }
}
