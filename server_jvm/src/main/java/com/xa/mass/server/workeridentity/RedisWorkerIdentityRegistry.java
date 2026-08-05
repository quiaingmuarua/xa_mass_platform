package com.xa.mass.server.workeridentity;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.Objects;
import java.util.UUID;

final class RedisWorkerIdentityRegistry
        implements WorkerIdentityRegistry, AutoCloseable {

    private final RedisClient redisClient;
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    RedisWorkerIdentityRegistry(RedisClient redisClient, String prefix) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.prefix = prefix;
    }

    @Override
    public String register(String workerGroupId, String clientWorkerKey) {
        String candidate = UUID.randomUUID().toString();
        String key = workerIdsKey(workerGroupId);
        commands().hsetnx(key, clientWorkerKey, candidate);
        return commands().hget(key, clientWorkerKey);
    }

    @Override
    public boolean matches(
            String workerGroupId,
            String clientWorkerKey,
            String workerId
    ) {
        return workerId.equals(
                commands().hget(
                        workerIdsKey(workerGroupId),
                        clientWorkerKey
                )
        );
    }

    private String workerIdsKey(String workerGroupId) {
        return "wi:{" + prefix + "}:worker-registrations:"
                + workerGroupId;
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

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }
}
