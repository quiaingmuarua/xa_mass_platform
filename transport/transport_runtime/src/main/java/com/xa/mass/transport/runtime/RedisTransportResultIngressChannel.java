package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.ResultIngressEntry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed result ingress inbox producer/consumer.
 */
public final class RedisTransportResultIngressChannel implements TransportResultIngressChannel, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.RESULT_INBOX;
    public static final int DEFAULT_MAX_QUEUED_RESULTS = 100_000;
    public static final long DEFAULT_VISIBILITY_TIMEOUT_MILLIS = 30_000L;
    private static final Logger logger = LoggerFactory.getLogger(RedisTransportResultIngressChannel.class);
    private static final long POLL_SLEEP_MILLIS = 100L;
    private static final int MAX_RECLAIM_PER_POLL = 100;

    private static final String OFFER_SCRIPT = """
            local readyKey = KEYS[1]
            local inflightKey = KEYS[2]
            local payloadsKey = KEYS[3]
            local maxQueuedItems = tonumber(ARGV[1])
            local ref = ARGV[2]
            local value = ARGV[3]
            if maxQueuedItems <= 0 then
              return {'BACKPRESSURE_REJECTED', 'queue capacity is exhausted'}
            end
            local queuedItems = redis.call('LLEN', readyKey) + redis.call('ZCARD', inflightKey)
            if queuedItems >= maxQueuedItems then
              return {'BACKPRESSURE_REJECTED', 'result inbox backlog is full'}
            end
            redis.call('HSET', payloadsKey, ref, value)
            redis.call('RPUSH', readyKey, ref)
            return {'ENQUEUED', ''}
            """;

    private static final String CLAIM_SCRIPT = """
            local readyKey = KEYS[1]
            local inflightKey = KEYS[2]
            local payloadsKey = KEYS[3]
            local visibilityDeadline = tonumber(ARGV[1])
            local ref = redis.call('LPOP', readyKey)
            if not ref then
              return {'EMPTY'}
            end
            local value = redis.call('HGET', payloadsKey, ref)
            if not value then
              return {'MISSING', ref}
            end
            redis.call('ZADD', inflightKey, visibilityDeadline, ref)
            return {'CLAIMED', ref, value}
            """;

    private static final String RECLAIM_EXPIRED_SCRIPT = """
            local readyKey = KEYS[1]
            local inflightKey = KEYS[2]
            local payloadsKey = KEYS[3]
            local now = tonumber(ARGV[1])
            local maxItems = tonumber(ARGV[2])
            local refs = redis.call('ZRANGEBYSCORE', inflightKey, '-inf', now, 'LIMIT', 0, maxItems)
            for _, ref in ipairs(refs) do
              redis.call('ZREM', inflightKey, ref)
              if redis.call('HEXISTS', payloadsKey, ref) == 1 then
                redis.call('RPUSH', readyKey, ref)
              end
            end
            return #refs
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String readyKey;
    private final String inflightKey;
    private final String payloadsKey;
    private final int maxQueuedResults;
    private final long visibilityTimeoutMillis;
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
                DEFAULT_VISIBILITY_TIMEOUT_MILLIS,
                true);
    }

    RedisTransportResultIngressChannel(RedisClient redisClient,
                                       String namespacePrefix,
                                       int maxQueuedResults,
                                       long visibilityTimeoutMillis,
                                       boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, maxQueuedResults, visibilityTimeoutMillis, ownsClient);
    }

    RedisTransportResultIngressChannel(StatefulRedisConnection<String, String> connection,
                                       String namespacePrefix,
                                       int maxQueuedResults) {
        this(null, connection, namespacePrefix, maxQueuedResults, DEFAULT_VISIBILITY_TIMEOUT_MILLIS, false);
    }

    RedisTransportResultIngressChannel(StatefulRedisConnection<String, String> connection,
                                       String namespacePrefix,
                                       int maxQueuedResults,
                                       long visibilityTimeoutMillis) {
        this(null, connection, namespacePrefix, maxQueuedResults, visibilityTimeoutMillis, false);
    }

    private RedisTransportResultIngressChannel(RedisClient redisClient,
                                               StatefulRedisConnection<String, String> connection,
                                               String namespacePrefix,
                                               int maxQueuedResults,
                                               long visibilityTimeoutMillis,
                                               boolean ownsClient) {
        if (maxQueuedResults <= 0) {
            throw new IllegalArgumentException("maxQueuedResults must be positive");
        }
        if (visibilityTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("visibilityTimeoutMillis must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        String prefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.readyKey = prefix + ":ready";
        this.inflightKey = prefix + ":inflight";
        this.payloadsKey = prefix + ":payloads";
        this.maxQueuedResults = maxQueuedResults;
        this.visibilityTimeoutMillis = visibilityTimeoutMillis;
        this.ownsClient = ownsClient;
    }

    @Override
    public boolean ingest(ResultIngressEntry entry) {
        if (entry == null || !running.get()) {
            return false;
        }
        String ref = entry.message().resultMessageId() + ":" + UUID.randomUUID();
        Object raw = commands.eval(
                OFFER_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{readyKey, inflightKey, payloadsKey},
                Integer.toString(maxQueuedResults),
                ref,
                codec.encode(entry)
        );
        return raw instanceof List<?> values
                && !values.isEmpty()
                && "ENQUEUED".equals(String.valueOf(values.getFirst()));
    }

    public ClaimedTransportResultIngress poll(long timeoutMillis) throws InterruptedException {
        if (!running.get()) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            reclaimExpired();
            ClaimedTransportResultIngress claimed = claimOnce();
            if (claimed != null) {
                return claimed;
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

    public void complete(ClaimedTransportResultIngress claimed) {
        if (claimed == null) {
            return;
        }
        ackRef(claimed.claimRef());
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
        commands.del(readyKey, inflightKey, payloadsKey);
    }

    private ClaimedTransportResultIngress claimOnce() {
        Object raw = commands.eval(
                CLAIM_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{readyKey, inflightKey, payloadsKey},
                Long.toString(System.currentTimeMillis() + visibilityTimeoutMillis)
        );
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return null;
        }
        String status = String.valueOf(values.getFirst());
        if ("EMPTY".equals(status)) {
            return null;
        }
        if ("MISSING".equals(status)) {
            logger.warn("Discarding result inbox reference without payload: ref={}",
                    values.size() > 1 ? values.get(1) : null);
            return null;
        }
        if (!"CLAIMED".equals(status) || values.size() < 3) {
            logger.warn("Unexpected Redis result inbox claim response: {}", values);
            return null;
        }
        String ref = String.valueOf(values.get(1));
        String value = String.valueOf(values.get(2));
        try {
            return new ClaimedTransportResultIngress(ref, codec.decode(value));
        } catch (RuntimeException ex) {
            logger.warn("Discarding invalid result inbox payload: ref={}", ref, ex);
            ackRef(ref);
            return null;
        }
    }

    private void reclaimExpired() {
        commands.eval(
                RECLAIM_EXPIRED_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{readyKey, inflightKey, payloadsKey},
                Long.toString(System.currentTimeMillis()),
                Integer.toString(MAX_RECLAIM_PER_POLL)
        );
    }

    private void ackRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        commands.zrem(inflightKey, ref);
        commands.hdel(payloadsKey, ref);
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
