package com.xa.mass.task.runtime.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.UUID;

final class RedisTaskRuntimeTestSupport {

    private static final String DEFAULT_REDIS_URI = "redis://127.0.0.1:6379/0";

    private RedisTaskRuntimeTestSupport() {
    }

    static String redisUri() {
        return System.getProperty("mass.redis.test.uri", DEFAULT_REDIS_URI);
    }

    static String namespace(String segment) {
        return "xa:mass:test:task-runtime:" + segment + ":" + UUID.randomUUID();
    }

    static RedisClient createClientOrSkip(String testLabel) {
        var redisUri = redisUri();
        try {
            var client = RedisClient.create(redisUri);
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                connection.sync().ping();
            }
            return client;
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Redis is not available for " + testLabel + ": " + exception.getMessage());
            throw exception;
        }
    }

    static void cleanupNamespace(String redisUri, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        var cleanupClient = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> cleanupConnection = cleanupClient.connect()) {
            cleanupNamespace(cleanupConnection.sync(), namespace);
        } finally {
            cleanupClient.shutdown();
        }
    }

    private static void cleanupNamespace(RedisCommands<String, String> commands, String namespace) {
        List<String> keys = commands.keys(namespace + ":*");
        if (!keys.isEmpty()) {
            commands.del(keys.toArray(String[]::new));
        }
    }
}
