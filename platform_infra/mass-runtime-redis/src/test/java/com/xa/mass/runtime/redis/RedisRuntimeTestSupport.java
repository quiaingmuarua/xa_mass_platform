package com.xa.mass.runtime.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.UUID;

public final class RedisRuntimeTestSupport {

    private static final String DEFAULT_REDIS_URI = "redis://127.0.0.1:6379/0";

    private RedisRuntimeTestSupport() {
    }

    public static String redisUri() {
        return System.getProperty("mass.redis.test.uri", DEFAULT_REDIS_URI);
    }

    public static String namespace(String segment) {
        return "xa:mass:test:" + segment + ":" + UUID.randomUUID();
    }

    public static RedisClient createClientOrSkip(String testLabel) {
        String redisUri = redisUri();
        try {
            RedisClient client = RedisClient.create(redisUri);
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                connection.sync().ping();
            }
            return client;
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for " + testLabel + ": " + ex.getMessage());
            throw ex;
        }
    }

    public static void cleanupNamespace(String redisUri, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        RedisClient cleanupClient = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> cleanupConnection = cleanupClient.connect()) {
            cleanupNamespace(cleanupConnection.sync(), namespace);
        } finally {
            cleanupClient.shutdown();
        }
    }

    public static void cleanupNamespace(RedisCommands<String, String> commands, String namespace) {
        if (commands == null || namespace == null || namespace.isBlank()) {
            return;
        }
        List<String> keys = commands.keys(namespace + ":*");
        if (!keys.isEmpty()) {
            commands.del(keys.toArray(String[]::new));
        }
    }
}
