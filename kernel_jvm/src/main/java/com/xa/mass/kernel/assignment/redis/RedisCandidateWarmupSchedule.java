package com.xa.mass.kernel.assignment.redis;

import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashSet;
import java.util.List;

public final class RedisCandidateWarmupSchedule
        implements CandidateWarmupSchedule, AutoCloseable {

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisCandidateWarmupSchedule(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this.redisClient = java.util.Objects.requireNonNull(
                redisClient,
                "redisClient"
        );
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public void scheduleCandidateWarmups(
            List<String> taskIds,
            long dueTimeMillis
    ) {
        if (taskIds == null) {
            throw new IllegalArgumentException("taskIds must be present");
        }
        if (dueTimeMillis <= 0) {
            throw new IllegalArgumentException(
                    "candidate warmup due time must be positive"
            );
        }
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        taskIds.forEach(taskId -> {
            requireNonBlank(taskId, "taskId");
            uniqueIds.add(taskId);
        });
        if (uniqueIds.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        ScoredValue<String>[] values = uniqueIds.stream()
                .map(taskId -> ScoredValue.just(dueTimeMillis, taskId))
                .toArray(ScoredValue[]::new);
        commands().zadd(warmupKey(), values);
    }

    @Override
    public List<String> consumeDueCandidateWarmups(
            long beforeTimeMillis,
            int limit
    ) {
        if (beforeTimeMillis <= 0) {
            throw new IllegalArgumentException(
                    "candidate warmup cutoff must be positive"
            );
        }
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "candidate warmup limit must be positive"
            );
        }
        List<String> taskIds = commands().zrangebyscore(
                warmupKey(),
                Double.NEGATIVE_INFINITY,
                beforeTimeMillis,
                0,
                limit
        );
        if (!taskIds.isEmpty()) {
            commands().zrem(warmupKey(), taskIds.toArray(String[]::new));
        }
        return List.copyOf(taskIds);
    }

    private RedisCommands<String, String> commands() {
        return connection().sync();
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null || !current.isOpen()) {
            synchronized (this) {
                current = connection;
                if (current == null || !current.isOpen()) {
                    current = redisClient.connect(StringCodec.UTF8);
                    connection = current;
                }
            }
        }
        return current;
    }

    private String warmupKey() {
        return keyspace.base() + ":dispatch:candidate_warmups";
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }
}
