package com.xa.mass.mock.example;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

public class LettuceDemo {
    public static void main(String[] args) {
        // redis://:password@host:port/0
        String redisUri = "redis://:tx1212112@1.12.219.17:6379/0";
        RedisClient redisClient = RedisClient.create(redisUri);

        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisCommands<String, String> syncCommands = connection.sync();

        // 简单的 set/get 测试
        syncCommands.set("hello", "world");
        String value = syncCommands.get("hello");
        System.out.println("hello = " + value);

        // 关闭连接
        connection.close();
        redisClient.shutdown();
    }
}
