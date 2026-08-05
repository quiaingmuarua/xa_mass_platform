package com.xa.mass.server.workerbinding;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class RedisWorkerBindingRegistry
        implements WorkerBindingRegistry, AutoCloseable {

    private final RedisClient redisClient;
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    RedisWorkerBindingRegistry(RedisClient redisClient, String prefix) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.prefix = prefix;
    }

    @Override
    public String bindIfAbsent(
            String workerId,
            String endpointManagerId
    ) {
        RedisCommands<String, String> commands = commands();
        String key = bindingKey(workerId);
        commands.hsetnx(key, workerId, endpointManagerId);
        return commands.hget(key, workerId);
    }

    @Override
    public String getEndpointManagerId(String workerId) {
        return commands().hget(bindingKey(workerId), workerId);
    }

    String bindingKey(String workerId) {
        return "wi:{" + prefix + "}:worker-bindings:" + bucket(workerId);
    }

    static String bucket(String workerId) {
        try {
            byte first = MessageDigest.getInstance("SHA-256").digest(
                    workerId.getBytes(StandardCharsets.UTF_8)
            )[0];
            return String.format("%02x", first & 0xff);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
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
