package com.xa.mass.transport.runtime;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.redis.queue.RedisKeyedBlockingQueueStore;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueNamespace;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueOptions;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisKeyedBlockingQueueStore readyQueue;
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
        String prefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.readyQueue = new RedisKeyedBlockingQueueStore(
                connection,
                new RedisKeyedQueueNamespace(prefix + ":ready"),
                RedisKeyedQueueOptions.defaults(maxQueuedResults)
        );
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
            KeyedQueueOfferResult result = readyQueue.offer(
                    DEFAULT_RESULT_QUEUE_KEY,
                    new KeyedQueueEntry(codec.encode(entry), entry.message().createdAtEpochMillis()),
                    maxQueuedResults
            );
            return result.status() == KeyedQueueOfferResult.Status.ENQUEUED;
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
        KeyedQueuePollResult result = readyQueue.poll(
                DEFAULT_RESULT_QUEUE_KEY,
                1,
                Math.max(0L, timeoutMillis),
                TimeUnit.MILLISECONDS
        );
        if (result.items().isEmpty()) {
            return null;
        }
        return decodeFirst(result.items());
    }

    @Override
    public void close() {
        running.set(false);
        readyQueue.shutdown();
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    public void shutdown() {
        close();
    }

    void clearForTest() {
        while (!readyQueue.drain(DEFAULT_RESULT_QUEUE_KEY, 1000).isEmpty()) {
            // Drain all test data.
        }
    }

    void pushRawReadyValueForTest(String value) {
        readyQueue.offer(
                DEFAULT_RESULT_QUEUE_KEY,
                new KeyedQueueEntry(value, System.currentTimeMillis()),
                maxQueuedResults
        );
    }

    private ResultIngressEntry decodeFirst(List<KeyedQueueEntry> entries) {
        for (KeyedQueueEntry entry : entries) {
            try {
                return codec.decode(entry.value());
            } catch (RuntimeException ex) {
                logger.warn("Discarding invalid result ingress queue payload", ex);
            }
        }
        return null;
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
