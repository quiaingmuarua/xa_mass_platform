package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed result inbox producer/consumer.
 *
 * <p>Transport nodes enqueue result envelopes; engine nodes drain and apply
 * them through the local engine result-ingest facade.</p>
 */
public final class RedisTaskResultIngestChannel implements TaskResultIngestChannel, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.RESULT_INBOX;
    public static final int DEFAULT_MAX_QUEUED_RESULTS = 100_000;
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
              return {'BACKPRESSURE_REJECTED', 'result inbox backlog is full'}
            end
            redis.call('RPUSH', queueKey, value)
            return {'ENQUEUED', ''}
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String queueKey;
    private final int maxQueuedResults;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TransportResultEnvelopeCodec codec = new TransportResultEnvelopeCodec();

    public RedisTaskResultIngestChannel(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_RESULTS);
    }

    public RedisTaskResultIngestChannel(String redisUri, String namespacePrefix, int maxQueuedResults) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedResults,
                true);
    }

    RedisTaskResultIngestChannel(RedisClient redisClient,
                                 String namespacePrefix,
                                 int maxQueuedResults,
                                 boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, maxQueuedResults, ownsClient);
    }

    RedisTaskResultIngestChannel(StatefulRedisConnection<String, String> connection,
                                 String namespacePrefix,
                                 int maxQueuedResults) {
        this(null, connection, namespacePrefix, maxQueuedResults, false);
    }

    private RedisTaskResultIngestChannel(RedisClient redisClient,
                                         StatefulRedisConnection<String, String> connection,
                                         String namespacePrefix,
                                         int maxQueuedResults,
                                         boolean ownsClient) {
        if (maxQueuedResults <= 0) {
            throw new IllegalArgumentException("maxQueuedResults must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.queueKey = normalizeRequired(namespacePrefix, "namespacePrefix") + ":queue";
        this.maxQueuedResults = maxQueuedResults;
        this.ownsClient = ownsClient;
    }

    @Override
    public boolean ingest(TaskResultReport report) {
        return false;
    }

    @Override
    public boolean ingest(TransportResultEnvelope envelope) {
        if (envelope == null || !running.get()) {
            return false;
        }
        Object raw = commands.eval(
                OFFER_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{queueKey},
                Integer.toString(maxQueuedResults),
                codec.encode(envelope)
        );
        return raw instanceof java.util.List<?> values
                && !values.isEmpty()
                && "ENQUEUED".equals(String.valueOf(values.getFirst()));
    }

    public TransportResultEnvelope pollEnvelope(long timeoutMillis) throws InterruptedException {
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

    int queuedResults() {
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
