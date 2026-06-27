package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed String keyed blocking queue store.
 */
public final class RedisKeyedBlockingQueueStore implements KeyedBlockingQueueStore {

    private static final char STORED_VALUE_DELIMITER = '|';
    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder KEY_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder VALUE_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder VALUE_DECODER = Base64.getDecoder();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisKeyedQueueNamespace namespace;
    private final RedisKeyedQueueOptions options;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RedisKeyedBlockingQueueStore(String redisUri,
                                        RedisKeyedQueueNamespace namespace,
                                        RedisKeyedQueueOptions options) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespace,
                options,
                true);
    }

    public RedisKeyedBlockingQueueStore(RedisClient redisClient,
                                        RedisKeyedQueueNamespace namespace,
                                        RedisKeyedQueueOptions options,
                                        boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespace,
                options,
                ownsClient);
    }

    public RedisKeyedBlockingQueueStore(StatefulRedisConnection<String, String> connection,
                                        RedisKeyedQueueNamespace namespace,
                                        RedisKeyedQueueOptions options) {
        this(null, connection, namespace, options, false);
    }

    private RedisKeyedBlockingQueueStore(RedisClient redisClient,
                                         StatefulRedisConnection<String, String> connection,
                                         RedisKeyedQueueNamespace namespace,
                                         RedisKeyedQueueOptions options,
                                         boolean ownsClient) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.options = Objects.requireNonNull(options, "options");
        this.ownsClient = ownsClient;
    }

    @Override
    public KeyedQueueOfferResult offer(String key, KeyedQueueEntry entry, int maxItemsPerKey) {
        if (isBlank(key) || entry == null) {
            return KeyedQueueOfferResult.invalid("key and entry must not be null");
        }
        if (maxItemsPerKey <= 0) {
            return KeyedQueueOfferResult.backpressureRejected("queue capacity is exhausted");
        }
        if (!running.get()) {
            return KeyedQueueOfferResult.unavailable("queue store is stopped");
        }
        String queueKey = queueKey(key);
        int effectiveMaxItems = Math.min(maxItemsPerKey, options.maxQueuedItems());
        try {
            if (commands.llen(queueKey) >= effectiveMaxItems) {
                return KeyedQueueOfferResult.backpressureRejected("queue is full");
            }
            commands.rpush(queueKey, encodeStoredValue(entry));
            return KeyedQueueOfferResult.enqueued();
        } catch (RuntimeException ex) {
            return KeyedQueueOfferResult.unavailable("redis queue is unavailable: " + ex.getMessage());
        }
    }

    @Override
    public List<KeyedQueueEntry> drain(String key, int maxItems) {
        if (isBlank(key) || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        String queueKey = queueKey(key);
        List<KeyedQueueEntry> drained = new ArrayList<>(Math.max(1, maxItems));
        try {
            for (int i = 0; i < maxItems; i++) {
                String storedValue = commands.lpop(queueKey);
                if (storedValue == null) {
                    break;
                }
                drained.add(decodeStoredValue(storedValue));
            }
            return drained.isEmpty() ? List.of() : List.copyOf(drained);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    @Override
    public KeyedQueuePollResult poll(String key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException {
        if (isBlank(key) || maxItems <= 0) {
            return KeyedQueuePollResult.invalid();
        }
        if (!running.get()) {
            return KeyedQueuePollResult.shutdown();
        }
        if (timeout <= 0) {
            List<KeyedQueueEntry> drained = drain(key, maxItems);
            if (!running.get()) {
                return KeyedQueuePollResult.shutdown();
            }
            return drained.isEmpty() ? KeyedQueuePollResult.empty() : KeyedQueuePollResult.delivered(drained);
        }

        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long sleepMillis = Math.max(1L, options.pollSleepInterval().toMillis());
        while (running.get()) {
            List<KeyedQueueEntry> drained = drain(key, maxItems);
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
    public int size(String key) {
        if (isBlank(key)) {
            return 0;
        }
        try {
            return Math.toIntExact(commands.llen(queueKey(key)));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (closed.compareAndSet(false, true)) {
            if (connection.isOpen()) {
                connection.close();
            }
            if (ownsClient && redisClient != null) {
                redisClient.shutdown();
            }
        }
    }

    private String queueKey(String key) {
        return namespace.queueKey(encodeKeyPart(key));
    }

    private static String encodeStoredValue(KeyedQueueEntry entry) {
        String encodedValue = VALUE_ENCODER.encodeToString(entry.value().getBytes(StandardCharsets.UTF_8));
        return entry.createdAtEpochMillis() + String.valueOf(STORED_VALUE_DELIMITER) + encodedValue;
    }

    private static KeyedQueueEntry decodeStoredValue(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            throw new IllegalArgumentException("stored queue value must not be blank");
        }
        int delimiterIndex = storedValue.indexOf(STORED_VALUE_DELIMITER);
        if (delimiterIndex <= 0 || delimiterIndex == storedValue.length() - 1) {
            throw new IllegalArgumentException("stored queue value is malformed");
        }
        long createdAtEpochMillis = Long.parseLong(storedValue.substring(0, delimiterIndex));
        String encodedValue = storedValue.substring(delimiterIndex + 1);
        return new KeyedQueueEntry(
                new String(VALUE_DECODER.decode(encodedValue), StandardCharsets.UTF_8),
                createdAtEpochMillis
        );
    }

    private static String encodeKeyPart(String key) {
        if (isBlank(key)) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return KEY_ENCODER.encodeToString(key.trim().getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused")
    private static String decodeKeyPart(String encodedKeyPart) {
        if (isBlank(encodedKeyPart)) {
            throw new IllegalArgumentException("encodedKeyPart must not be blank");
        }
        return new String(KEY_DECODER.decode(encodedKeyPart), StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
