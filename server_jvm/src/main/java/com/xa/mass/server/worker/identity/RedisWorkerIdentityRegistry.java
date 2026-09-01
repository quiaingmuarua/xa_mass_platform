package com.xa.mass.server.worker.identity;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.Objects;
import java.util.UUID;

final class RedisWorkerIdentityRegistry
        implements WorkerIdentityRegistry, AutoCloseable {

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    RedisWorkerIdentityRegistry(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
    }

    @Override
    public String register(
            String workerGroupId,
            String registrationKey
    ) {
        String candidate = UUID.randomUUID().toString();
        String key = workerIdsKey(workerGroupId);
        commands().hsetnx(key, registrationKey, candidate);
        return commands().hget(key, registrationKey);
    }

    @Override
    public boolean matches(
            String workerGroupId,
            String registrationKey,
            String workerId
    ) {
        return workerId.equals(
                commands().hget(
                        workerIdsKey(workerGroupId),
                        registrationKey
                )
        );
    }

    private String workerIdsKey(String workerGroupId) {
        return keyspace.base() + ":worker:identity:" + workerGroupId;
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
