package com.xa.mass.server.example;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

public class LettuceDemo {
    public static void main(String[] args) {
        // Configure via environment variable: REDIS_URI=redis://:password@host:port/0
        String redisUri = System.getenv().getOrDefault("REDIS_URI", "redis://localhost:6379/0");
        RedisClient redisClient = RedisClient.create(redisUri);

        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisCommands<String, String> syncCommands = connection.sync();

        syncCommands.set("hello", "world");
        String value = syncCommands.get("hello");
        System.out.println("hello = " + value);

        connection.close();
        redisClient.shutdown();
    }
}
