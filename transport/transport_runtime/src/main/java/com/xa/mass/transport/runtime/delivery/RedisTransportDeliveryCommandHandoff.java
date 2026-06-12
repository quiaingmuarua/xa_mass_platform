package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed non-blocking delivery command handoff.
 */
public final class RedisTransportDeliveryCommandHandoff implements TransportDeliveryCommandHandoff, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DELIVERY_COMMAND;
    public static final int DEFAULT_MAX_QUEUED_BATCHES_PER_LANE = 100_000;
    private static final long POLL_SLEEP_MILLIS = 50L;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String OFFER_SCRIPT = """
            local queueKey = KEYS[1]
            local lanesKey = KEYS[2]
            local readyLanesKey = KEYS[3]
            local maxQueuedItems = tonumber(ARGV[1])
            local laneKey = ARGV[2]
            local value = ARGV[3]
            if maxQueuedItems <= 0 then
              return {'BACKPRESSURE', 'queue capacity is exhausted'}
            end
            local queuedItems = redis.call('LLEN', queueKey)
            if queuedItems >= maxQueuedItems then
              return {'BACKPRESSURE', 'delivery command lane backlog is full'}
            end
            redis.call('RPUSH', queueKey, value)
            redis.call('SADD', lanesKey, laneKey)
            redis.call('SADD', readyLanesKey, laneKey)
            return {'QUEUED', ''}
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final String localTransportNodeId;
    private final int maxQueuedBatchesPerLane;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TransportDeliveryCommandBatchCodec codec = new TransportDeliveryCommandBatchCodec();

    public RedisTransportDeliveryCommandHandoff(String redisUri,
                                                String namespacePrefix,
                                                String localTransportNodeId,
                                                int maxQueuedBatchesPerLane) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                localTransportNodeId,
                maxQueuedBatchesPerLane,
                true
        );
    }

    RedisTransportDeliveryCommandHandoff(RedisClient redisClient,
                                         String namespacePrefix,
                                         String localTransportNodeId,
                                         int maxQueuedBatchesPerLane,
                                         boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                localTransportNodeId,
                maxQueuedBatchesPerLane,
                ownsClient
        );
    }

    RedisTransportDeliveryCommandHandoff(StatefulRedisConnection<String, String> connection,
                                         String namespacePrefix,
                                         String localTransportNodeId,
                                         int maxQueuedBatchesPerLane) {
        this(null, connection, namespacePrefix, localTransportNodeId, maxQueuedBatchesPerLane, false);
    }

    private RedisTransportDeliveryCommandHandoff(RedisClient redisClient,
                                                 StatefulRedisConnection<String, String> connection,
                                                 String namespacePrefix,
                                                 String localTransportNodeId,
                                                 int maxQueuedBatchesPerLane,
                                                 boolean ownsClient) {
        if (maxQueuedBatchesPerLane <= 0) {
            throw new IllegalArgumentException("maxQueuedBatchesPerLane must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.localTransportNodeId = normalizeNullable(localTransportNodeId);
        this.maxQueuedBatchesPerLane = maxQueuedBatchesPerLane;
        this.ownsClient = ownsClient;
    }

    @Override
    public List<DispatchOutcome> offer(DeliveryCommandBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!running.get()) {
            return batch.commands().stream()
                    .map(command -> DispatchOutcome.shutdown(command, "delivery command handoff is stopped"))
                    .toList();
        }
        String laneKey = physicalLaneKey(batch.deliveryQueueKey(), batch.targetTransportNodeId());
        Object raw = commands.eval(
                OFFER_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        laneQueueKey(laneKey),
                        lanesKey(),
                        readyLanesKey(batch.targetTransportNodeId())
                },
                Integer.toString(maxQueuedBatchesPerLane),
                laneKey,
                codec.encode(batch)
        );
        List<?> values = raw instanceof List<?> list ? list : List.of();
        String status = values.isEmpty() ? "BACKPRESSURE" : String.valueOf(values.getFirst());
        String reason = values.size() > 1 ? String.valueOf(values.get(1)) : "delivery command offer failed";
        if ("QUEUED".equals(status)) {
            return batch.commands().stream()
                    .map(DispatchOutcome::queued)
                    .toList();
        }
        return batch.commands().stream()
                .map(command -> DispatchOutcome.backpressure(command, reason))
                .toList();
    }

    @Override
    public DeliveryCommandBatch poll(long timeoutMillis) throws InterruptedException {
        if (!running.get() || localTransportNodeId == null) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            String laneKey = commands.spop(readyLanesKey(localTransportNodeId));
            if (laneKey != null && !laneKey.isBlank()) {
                String json = commands.lpop(laneQueueKey(laneKey));
                if (json != null) {
                    if (commands.llen(laneQueueKey(laneKey)) > 0L) {
                        commands.sadd(readyLanesKey(localTransportNodeId), laneKey);
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

    int queuedBatches(String deliveryQueueKey, String targetTransportNodeId) {
        return Math.toIntExact(commands.llen(laneQueueKey(physicalLaneKey(deliveryQueueKey, targetTransportNodeId))));
    }

    void clearForTest(String deliveryQueueKey, String targetTransportNodeId) {
        String laneKey = physicalLaneKey(deliveryQueueKey, targetTransportNodeId);
        if (localTransportNodeId != null) {
            commands.del(laneQueueKey(laneKey));
            commands.srem(readyLanesKey(localTransportNodeId), laneKey);
        }
        commands.srem(lanesKey(), laneKey);
    }

    private String laneQueueKey(String laneKey) {
        return namespacePrefix
                + ":lane:" + encodeToken(normalizeRequired(laneKey, "laneKey"))
                + ":q";
    }

    private String lanesKey() {
        return namespacePrefix + ":lanes";
    }

    private String readyLanesKey(String transportNodeId) {
        return namespacePrefix + ":node:" + normalizeRequired(transportNodeId, "transportNodeId") + ":ready-lanes";
    }

    private static String physicalLaneKey(String deliveryQueueKey, String targetTransportNodeId) {
        return normalizeRequired(deliveryQueueKey, "deliveryQueueKey")
                + "\n"
                + normalizeRequired(targetTransportNodeId, "targetTransportNodeId");
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
