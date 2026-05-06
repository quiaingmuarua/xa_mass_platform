package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.queue.KeyedQueueSnapshot;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed keyed blocking queue store.
 *
 * <p>This first slice implements the enqueue/admission path only. Drain, poll,
 * and snapshot will be added in follow-up work once the Redis queue contract is
 * fully wired through transport and tested against the in-memory reference
 * semantics.
 */
public final class RedisKeyedBlockingQueueStore<K, V> implements KeyedBlockingQueueStore<K, V> {

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

    RedisKeyedBlockingQueueStore(RedisClient redisClient,
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

    RedisKeyedBlockingQueueStore(StatefulRedisConnection<String, String> connection,
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

        Object rawResponse = commands.eval(
                scripts.offerScript(),
                ScriptOutputType.MULTI,
                new String[]{queueKey, metaKey, activeQueuesKey, globalStatsKey},
                encodedValue,
                String.valueOf(entry.createdAtEpochMillis()),
                String.valueOf(maxItemsPerKey),
                String.valueOf(options.maxQueuedItems()),
                encodedKeyPart
        );
        return mapOfferResponse(rawResponse);
    }

    @Override
    public List<KeyedQueueEntry<V>> drain(K key, int maxItems) {
        throw new UnsupportedOperationException("Redis keyed queue drain is not implemented yet");
    }

    @Override
    public KeyedQueuePollResult<V> poll(K key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException("Redis keyed queue poll is not implemented yet");
    }

    @Override
    public KeyedQueueSnapshot<K> snapshot() {
        throw new UnsupportedOperationException("Redis keyed queue snapshot is not implemented yet");
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (closed.compareAndSet(false, true)) {
            connection.close();
            if (ownsClient && redisClient != null) {
                redisClient.shutdown();
            }
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
}
