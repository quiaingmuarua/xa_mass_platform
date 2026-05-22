package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.NodeTargetedTaskDispatchHandoff;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed node-targeted dispatch handoff.
 */
public final class RedisNodeTargetedTaskDispatchHandoff implements NodeTargetedTaskDispatchHandoff, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = "xa:mass:transport:dispatch-node";
    public static final int DEFAULT_MAX_QUEUED_BATCHES_PER_NODE = 100_000;
    private static final long POLL_SLEEP_MILLIS = 100L;
    private static final String OFFER_SCRIPT = """
            local queueKey = KEYS[1]
            local maxQueuedItems = tonumber(ARGV[1])
            local value = ARGV[2]
            local queuedItems = redis.call('LLEN', queueKey)
            if queuedItems >= maxQueuedItems then
              return {'BACKPRESSURE_REJECTED', 'dispatch node inbox backlog is full'}
            end
            redis.call('RPUSH', queueKey, value)
            return {'ENQUEUED', ''}
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final String localTransportNodeId;
    private final int maxQueuedBatchesPerNode;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TaskDispatchBatchCodec codec = new TaskDispatchBatchCodec();

    public RedisNodeTargetedTaskDispatchHandoff(String redisUri,
                                                String namespacePrefix,
                                                String localTransportNodeId,
                                                int maxQueuedBatchesPerNode) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                localTransportNodeId,
                maxQueuedBatchesPerNode,
                true
        );
    }

    RedisNodeTargetedTaskDispatchHandoff(RedisClient redisClient,
                                         String namespacePrefix,
                                         String localTransportNodeId,
                                         int maxQueuedBatchesPerNode,
                                         boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, localTransportNodeId, maxQueuedBatchesPerNode, ownsClient);
    }

    RedisNodeTargetedTaskDispatchHandoff(StatefulRedisConnection<String, String> connection,
                                         String namespacePrefix,
                                         String localTransportNodeId,
                                         int maxQueuedBatchesPerNode) {
        this(null, connection, namespacePrefix, localTransportNodeId, maxQueuedBatchesPerNode, false);
    }

    private RedisNodeTargetedTaskDispatchHandoff(RedisClient redisClient,
                                                 StatefulRedisConnection<String, String> connection,
                                                 String namespacePrefix,
                                                 String localTransportNodeId,
                                                 int maxQueuedBatchesPerNode,
                                                 boolean ownsClient) {
        if (maxQueuedBatchesPerNode <= 0) {
            throw new IllegalArgumentException("maxQueuedBatchesPerNode must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.localTransportNodeId = normalizeNullable(localTransportNodeId);
        this.maxQueuedBatchesPerNode = maxQueuedBatchesPerNode;
        this.ownsClient = ownsClient;
    }

    @Override
    public void submit(String transportNodeId, TaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        String nodeId = normalizeRequired(transportNodeId, "transportNodeId");
        if (!running.get()) {
            throw new IllegalStateException("node-targeted task dispatch handoff is stopped");
        }
        String encoded = codec.encode(batch);
        while (running.get()) {
            Object raw = commands.eval(
                    OFFER_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{queueKey(nodeId)},
                    Integer.toString(maxQueuedBatchesPerNode),
                    encoded
            );
            if (!(raw instanceof java.util.List<?> values) || values.isEmpty()) {
                throw new IllegalStateException("redis node dispatch handoff returned no response");
            }
            String code = String.valueOf(values.getFirst());
            if ("ENQUEUED".equals(code)) {
                return;
            }
            try {
                Thread.sleep(POLL_SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while submitting node-targeted task dispatch batch", e);
            }
        }
        throw new IllegalStateException("node-targeted task dispatch handoff is stopped");
    }

    @Override
    public void submit(TaskDispatchBatch batch) {
        submit(requireLocalTransportNodeId(), batch);
    }

    @Override
    public TaskDispatchBatch poll(String transportNodeId, long timeoutMillis) throws InterruptedException {
        String nodeId = normalizeRequired(transportNodeId, "transportNodeId");
        if (!running.get()) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            String json = commands.lpop(queueKey(nodeId));
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
    public TaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
        return poll(requireLocalTransportNodeId(), timeoutMillis);
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

    int queuedBatches(String transportNodeId) {
        return Math.toIntExact(commands.llen(queueKey(normalizeRequired(transportNodeId, "transportNodeId"))));
    }

    void clearForTest(String transportNodeId) {
        commands.del(queueKey(normalizeRequired(transportNodeId, "transportNodeId")));
    }

    private String queueKey(String transportNodeId) {
        return namespacePrefix + ":node:" + transportNodeId + ":queue";
    }

    private String requireLocalTransportNodeId() {
        if (localTransportNodeId == null) {
            throw new IllegalStateException("local transportNodeId is not configured");
        }
        return localTransportNodeId;
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
