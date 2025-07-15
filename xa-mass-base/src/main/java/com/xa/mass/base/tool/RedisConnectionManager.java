package com.xa.mass.base.tool;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Redis连接管理器（全局单例）
 * 负责统一管理Lettuce RedisClient和StatefulRedisConnection
 */
public class RedisConnectionManager {
    private static volatile RedisClient client;
    private static volatile StatefulRedisConnection<String, String> connection;
    private static volatile boolean initialized = false;

    private RedisConnectionManager() {}

    /**
     * 初始化全局Redis连接（只初始化一次）
     * @param host Redis主机
     * @param port Redis端口
     * @param password Redis密码（可为null）
     * @param database 数据库索引
     */
    public static synchronized void init(String host, int port, String password, int database) {
        if (initialized) return;
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database);
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password);
        }
        client = RedisClient.create(uriBuilder.build());
        connection = client.connect();
        initialized = true;
    }

    /**
     * 获取全局RedisClient
     */
    public static RedisClient getClient() {
        checkInit();
        return client;
    }

    /**
     * 获取全局StatefulRedisConnection
     */
    public static StatefulRedisConnection<String, String> getConnection() {
        checkInit();
        return connection;
    }

    /**
     * 关闭全局连接
     */
    public static synchronized void shutdown() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (client != null) {
            client.shutdown();
            client = null;
        }
        initialized = false;
    }

    private static void checkInit() {
        if (!initialized) {
            throw new IllegalStateException("RedisConnectionManager not initialized. Call init() first.");
        }
    }
} 