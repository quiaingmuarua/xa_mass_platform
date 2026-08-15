package com.xa.mass.server.workerbinding;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RedisWorkerBindingRegistry
        implements WorkerBindingRegistry, AutoCloseable {

    private static final int MAX_BATCH_SIZE = 100;

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

    @Override
    public Map<String, String> getEndpointManagerIds(
            List<String> workerIds
    ) {
        requireBatch(workerIds);
        if (workerIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> idsByKey = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            idsByKey.computeIfAbsent(
                    bindingKey(workerId),
                    ignored -> new java.util.ArrayList<>()
            ).add(workerId);
        }

        RedisAsyncCommands<String, String> async = connection().async();
        Map<String, RedisFuture<List<KeyValue<String, String>>>> futures =
                new LinkedHashMap<>();
        idsByKey.forEach((key, ids) -> futures.put(
                key,
                async.hmget(key, ids.toArray(String[]::new))
        ));

        Map<String, String> loadedByWorker = new LinkedHashMap<>();
        futures.forEach((key, future) -> {
            for (KeyValue<String, String> row
                    : future.toCompletableFuture().join()) {
                loadedByWorker.put(
                        row.getKey(),
                        row.hasValue() ? row.getValue() : null
                );
            }
        });
        Map<String, String> ordered = new LinkedHashMap<>();
        workerIds.forEach(workerId -> ordered.put(
                workerId,
                loadedByWorker.get(workerId)
        ));
        return Collections.unmodifiableMap(ordered);
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

    private static void requireBatch(List<String> workerIds) {
        if (workerIds == null) {
            throw new IllegalArgumentException(
                    "workerIds must be present"
            );
        }
        if (workerIds.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "workerIds must contain at most 100 entries"
            );
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            if (workerId == null || workerId.isBlank()) {
                throw new IllegalArgumentException(
                        "workerId must be non-blank"
                );
            }
            if (!unique.add(workerId)) {
                throw new IllegalArgumentException(
                        "workerIds must be unique"
                );
            }
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
