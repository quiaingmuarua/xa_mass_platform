package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed best-effort result ingress queue.
 */
public final class RedisTransportResultIngressChannel implements TransportResultIngressChannel,
        TransportResultIngressQueue,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.RESULT_INGRESS;
    public static final int DEFAULT_MAX_QUEUED_RESULTS = 100_000;
    private static final Logger logger = LoggerFactory.getLogger(RedisTransportResultIngressChannel.class);
    private static final long POLL_SLEEP_MILLIS = 100L;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String readyKey;
    private final int maxQueuedResults;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ResultIngressEntryCodec codec = new ResultIngressEntryCodec();

    public RedisTransportResultIngressChannel(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_RESULTS);
    }

    public RedisTransportResultIngressChannel(String redisUri, String namespacePrefix, int maxQueuedResults) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedResults,
                true);
    }

    RedisTransportResultIngressChannel(RedisClient redisClient,
                                       String namespacePrefix,
                                       int maxQueuedResults,
                                       boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, maxQueuedResults, ownsClient);
    }

    RedisTransportResultIngressChannel(StatefulRedisConnection<String, String> connection,
                                       String namespacePrefix,
                                       int maxQueuedResults) {
        this(null, connection, namespacePrefix, maxQueuedResults, false);
    }

    private RedisTransportResultIngressChannel(RedisClient redisClient,
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
        String prefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.readyKey = prefix + ":ready";
        this.maxQueuedResults = maxQueuedResults;
        this.ownsClient = ownsClient;
    }

    @Override
    public boolean ingest(ResultIngressEntry entry) {
        return offer(DEFAULT_RESULT_QUEUE_KEY, entry);
    }

    @Override
    public boolean offer(String resultQueueKey, ResultIngressEntry entry) {
        requireDefaultQueue(resultQueueKey);
        if (entry == null || !running.get()) {
            return false;
        }
        try {
            if (commands.llen(readyKey) >= maxQueuedResults) {
                return false;
            }
            commands.rpush(readyKey, codec.encode(entry));
            return true;
        } catch (RuntimeException ex) {
            logger.warn("Redis result ingress offer failed: resultMessageId={}",
                    entry.message().resultMessageId(), ex);
            return false;
        }
    }

    public ResultIngressEntry poll(long timeoutMillis) throws InterruptedException {
        return poll(DEFAULT_RESULT_QUEUE_KEY, timeoutMillis);
    }

    @Override
    public ResultIngressEntry poll(String resultQueueKey, long timeoutMillis) throws InterruptedException {
        requireDefaultQueue(resultQueueKey);
        if (!running.get()) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            ResultIngressEntry entry = pollOnce();
            if (entry != null) {
                return entry;
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

    void clearForTest() {
        commands.del(readyKey);
    }

    private ResultIngressEntry pollOnce() {
        String encoded = commands.lpop(readyKey);
        if (encoded == null) {
            return null;
        }
        try {
            return codec.decode(encoded);
        } catch (RuntimeException ex) {
            logger.warn("Discarding invalid result ingress queue payload", ex);
            return null;
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static void requireDefaultQueue(String resultQueueKey) {
        String normalized = normalizeRequired(resultQueueKey, "resultQueueKey");
        if (!TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported resultQueueKey: " + resultQueueKey);
        }
    }
}
