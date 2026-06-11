package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed route-key dispatch handoff.
 */
public final class RedisRouteTargetedTaskDispatchHandoff implements RouteTargetedTaskDispatchHandoff, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DISPATCH_ROUTE;
    public static final int DEFAULT_MAX_QUEUED_BATCHES_PER_ROUTE = 100_000;
    private static final long POLL_SLEEP_MILLIS = 50L;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final String localTransportNodeId;
    private final int maxQueuedBatchesPerRoute;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RouteTargetedTaskDispatchBatchCodec codec = new RouteTargetedTaskDispatchBatchCodec();

    public RedisRouteTargetedTaskDispatchHandoff(String redisUri,
                                                 String namespacePrefix,
                                                 String localTransportNodeId,
                                                 int maxQueuedBatchesPerRoute) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                localTransportNodeId,
                maxQueuedBatchesPerRoute,
                true
        );
    }

    RedisRouteTargetedTaskDispatchHandoff(RedisClient redisClient,
                                          String namespacePrefix,
                                          String localTransportNodeId,
                                          int maxQueuedBatchesPerRoute,
                                          boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                localTransportNodeId,
                maxQueuedBatchesPerRoute,
                ownsClient
        );
    }

    RedisRouteTargetedTaskDispatchHandoff(StatefulRedisConnection<String, String> connection,
                                          String namespacePrefix,
                                          String localTransportNodeId,
                                          int maxQueuedBatchesPerRoute) {
        this(null, connection, namespacePrefix, localTransportNodeId, maxQueuedBatchesPerRoute, false);
    }

    private RedisRouteTargetedTaskDispatchHandoff(RedisClient redisClient,
                                                  StatefulRedisConnection<String, String> connection,
                                                  String namespacePrefix,
                                                  String localTransportNodeId,
                                                  int maxQueuedBatchesPerRoute,
                                                  boolean ownsClient) {
        if (maxQueuedBatchesPerRoute <= 0) {
            throw new IllegalArgumentException("maxQueuedBatchesPerRoute must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.localTransportNodeId = normalizeNullable(localTransportNodeId);
        this.maxQueuedBatchesPerRoute = maxQueuedBatchesPerRoute;
        this.ownsClient = ownsClient;
    }

    @Override
    public void submit(RouteTargetedTaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!running.get()) {
            throw new IllegalStateException("route-targeted task dispatch handoff is stopped");
        }
        String targetTransportNodeId = batch.targetTransportNodeId();
        String routeQueueKey = routeQueueKey(batch.routeKey(), targetTransportNodeId);
        String encoded = codec.encode(batch);
        while (running.get()) {
            long queued = commands.llen(routeQueueKey);
            if (queued < maxQueuedBatchesPerRoute) {
                commands.rpush(routeQueueKey, encoded);
                commands.sadd(routesKey(), batch.routeKey());
                commands.sadd(readyRoutesKey(targetTransportNodeId), batch.routeKey());
                return;
            }
            try {
                Thread.sleep(POLL_SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while submitting route-targeted dispatch batch", e);
            }
        }
        throw new IllegalStateException("route-targeted task dispatch handoff is stopped");
    }

    @Override
    public RouteTargetedTaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
        if (!running.get() || localTransportNodeId == null) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            String routeKey = commands.spop(readyRoutesKey(localTransportNodeId));
            if (routeKey != null && !routeKey.isBlank()) {
                String routeQueueKey = routeQueueKey(routeKey, localTransportNodeId);
                String json = commands.lpop(routeQueueKey);
                if (json != null) {
                    if (commands.llen(routeQueueKey) > 0L) {
                        commands.sadd(readyRoutesKey(localTransportNodeId), routeKey);
                    }
                    return codec.decode(json);
                }
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

    int queuedBatches(String routeKey) {
        if (localTransportNodeId == null) {
            return 0;
        }
        return Math.toIntExact(commands.llen(routeQueueKey(routeKey, localTransportNodeId)));
    }

    void clearForTest(String routeKey) {
        if (localTransportNodeId != null) {
            commands.del(routeQueueKey(routeKey, localTransportNodeId));
            commands.srem(readyRoutesKey(localTransportNodeId), routeKey);
        }
        commands.srem(routesKey(), routeKey);
    }

    private String routeQueueKey(String routeKey, String transportNodeId) {
        return namespacePrefix
                + ":route:" + encodeToken(normalizeRequired(routeKey, "routeKey"))
                + ":node:" + encodeToken(normalizeRequired(transportNodeId, "transportNodeId"))
                + ":q";
    }

    private String routesKey() {
        return namespacePrefix + ":routes";
    }

    private String readyRoutesKey(String transportNodeId) {
        return namespacePrefix + ":node:" + normalizeRequired(transportNodeId, "transportNodeId") + ":ready-routes";
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
