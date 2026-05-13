package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed producer/consumer for retryable dispatch failure compensation.
 */
public final class RedisTransportDispatchFailureChannel implements TransportDispatchFailureHandler, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = "xa:mass:transport:dispatch-failure";
    public static final int DEFAULT_MAX_QUEUED_FAILURES = 100_000;
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
              return {'BACKPRESSURE_REJECTED', 'dispatch failure inbox backlog is full'}
            end
            redis.call('RPUSH', queueKey, value)
            return {'ENQUEUED', ''}
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String queueKey;
    private final int maxQueuedFailures;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TransportDispatchFailureEventCodec codec = new TransportDispatchFailureEventCodec();

    public RedisTransportDispatchFailureChannel(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_FAILURES);
    }

    public RedisTransportDispatchFailureChannel(String redisUri, String namespacePrefix, int maxQueuedFailures) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedFailures,
                true);
    }

    RedisTransportDispatchFailureChannel(RedisClient redisClient,
                                         String namespacePrefix,
                                         int maxQueuedFailures,
                                         boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, maxQueuedFailures, ownsClient);
    }

    RedisTransportDispatchFailureChannel(StatefulRedisConnection<String, String> connection,
                                         String namespacePrefix,
                                         int maxQueuedFailures) {
        this(null, connection, namespacePrefix, maxQueuedFailures, false);
    }

    private RedisTransportDispatchFailureChannel(RedisClient redisClient,
                                                 StatefulRedisConnection<String, String> connection,
                                                 String namespacePrefix,
                                                 int maxQueuedFailures,
                                                 boolean ownsClient) {
        if (maxQueuedFailures <= 0) {
            throw new IllegalArgumentException("maxQueuedFailures must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.queueKey = normalizeRequired(namespacePrefix, "namespacePrefix") + ":queue";
        this.maxQueuedFailures = maxQueuedFailures;
        this.ownsClient = ownsClient;
    }

    @Override
    public boolean compensate(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings, String detail) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty() || !running.get()) {
            return false;
        }
        Object raw = commands.eval(
                OFFER_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{queueKey},
                Integer.toString(maxQueuedFailures),
                codec.encode(new TransportDispatchFailureEvent(task, dispatchBindings, detail))
        );
        return raw instanceof java.util.List<?> values
                && !values.isEmpty()
                && "ENQUEUED".equals(String.valueOf(values.getFirst()));
    }

    public TransportDispatchFailureEvent pollFailure(long timeoutMillis) throws InterruptedException {
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
    public void close() {
        running.set(false);
        if (connection.isOpen()) {
            connection.close();
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    public void shutdown() {
        close();
    }

    int queuedFailures() {
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
