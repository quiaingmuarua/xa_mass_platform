package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed engine -> transport dispatch handoff.
 *
 * <p>This is a shared process-boundary queue. Shutdown closes only this local
 * client and intentionally does not clear Redis data.</p>
 */
public final class RedisTaskDispatchHandoff implements com.xa.mass.base.runtime.dispatch.TaskDispatchHandoff, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DISPATCH_HANDOFF;
    public static final int DEFAULT_MAX_QUEUED_BATCHES = 100_000;
    private static final long POLL_SLEEP_MILLIS = 100L;
    private static final String OFFER_SCRIPT = """
            local queueKey = KEYS[1]
            local maxQueuedItems = tonumber(ARGV[1])
            local value = ARGV[2]
            if maxQueuedItems <= 0 then
              return {'BACKPRESSURE_REJECTED', 'queue capacity is exhausted'}
            end
            local queuedItems = redis.call('LLEN', queueKey)
            if queuedItems >= maxQueuedItems then
              return {'BACKPRESSURE_REJECTED', 'dispatch handoff backlog is full'}
            end
            redis.call('RPUSH', queueKey, value)
            return {'ENQUEUED', ''}
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String queueKey;
    private final int maxQueuedBatches;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TaskDispatchBatchCodec codec = new TaskDispatchBatchCodec();

    public RedisTaskDispatchHandoff(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_BATCHES);
    }

    public RedisTaskDispatchHandoff(String redisUri, String namespacePrefix, int maxQueuedBatches) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedBatches,
                true
        );
    }

    RedisTaskDispatchHandoff(RedisClient redisClient,
                             String namespacePrefix,
                             int maxQueuedBatches,
                             boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, maxQueuedBatches, ownsClient);
    }

    RedisTaskDispatchHandoff(StatefulRedisConnection<String, String> connection,
                             String namespacePrefix,
                             int maxQueuedBatches) {
        this(null, connection, namespacePrefix, maxQueuedBatches, false);
    }

    private RedisTaskDispatchHandoff(RedisClient redisClient,
                                     StatefulRedisConnection<String, String> connection,
                                     String namespacePrefix,
                                     int maxQueuedBatches,
                                     boolean ownsClient) {
        if (maxQueuedBatches <= 0) {
            throw new IllegalArgumentException("maxQueuedBatches must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.queueKey = normalizeRequired(namespacePrefix, "namespacePrefix") + ":queue";
        this.maxQueuedBatches = maxQueuedBatches;
        this.ownsClient = ownsClient;
    }

    @Override
    public void submit(TaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!running.get()) {
            throw new IllegalStateException("task dispatch handoff is stopped");
        }
        String encoded = codec.encode(batch);
        while (running.get()) {
            Object raw = commands.eval(
                    OFFER_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{queueKey},
                    Integer.toString(maxQueuedBatches),
                    encoded
            );
            if (!(raw instanceof java.util.List<?> values) || values.isEmpty()) {
                throw new IllegalStateException("redis dispatch handoff returned no response");
            }
            String code = String.valueOf(values.getFirst());
            if ("ENQUEUED".equals(code)) {
                return;
            }
            if (!"BACKPRESSURE_REJECTED".equals(code)) {
                String reason = values.size() > 1 ? String.valueOf(values.get(1)) : "dispatch handoff enqueue failed";
                throw new IllegalStateException(reason == null || reason.isBlank() ? "dispatch handoff enqueue failed" : reason);
            }
            try {
                Thread.sleep(POLL_SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while submitting task dispatch batch", e);
            }
        }
        throw new IllegalStateException("task dispatch handoff is stopped");
    }

    @Override
    public TaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
        if (!running.get()) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            String json = commands.lpop(queueKey);
            if (json != null) {
                return codec.decode(json);
            }
            if (timeoutMillis <= 0L) {
                return null;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return null;
            }
            Thread.sleep(Math.min(POLL_SLEEP_MILLIS, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        } while (running.get());
        return null;
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

    int queuedBatches() {
        return Math.toIntExact(commands.llen(queueKey));
    }

    void clearForTest() {
        commands.del(queueKey);
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
