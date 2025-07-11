package com.xa.mass.base.channel.queue.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import io.lettuce.core.KeyValue;
import java.util.concurrent.TimeUnit;

/**
 * 基于Lettuce的Redis消息队列实现
 * 使用单连接多路复用，性能更高，无需传统连接池
 */
public class LettuceRedisQueue<T> implements MessageQueue<T> {
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String queueKey;
    private final String name;
    private final Gson gson;
    private final Class<T> messageType;

    /**
     * 创建Lettuce Redis消息队列
     * 
     * @param host Redis服务器地址
     * @param port Redis服务器端口
     * @param password Redis密码（可为null）
     * @param database 数据库索引
     * @param queueKey Redis中的队列键名
     * @param name 队列名称
     * @param messageType 消息类型
     */
    public LettuceRedisQueue(String host, int port, String password, int database, 
                           String queueKey, String name, Class<T> messageType) {
        this.queueKey = queueKey;
        this.name = name;
        this.messageType = messageType;
        this.gson = new GsonBuilder().create();

        // 构建Redis URI
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database);
        
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password);
        }

        // 创建Redis客户端（单连接，支持多路复用）
        this.redisClient = RedisClient.create(uriBuilder.build());
        
        // 获取连接
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    /**
     * 简化构造函数
     * 
     * @param queueKey Redis中的队列键名
     * @param name 队列名称
     * @param messageType 消息类型
     */
    public LettuceRedisQueue(String queueKey, String name, Class<T> messageType) {
        this("localhost", 6379, null, 0, queueKey, name, messageType);
    }

    @Override
    public void offer(T message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        try {
            String jsonMessage = gson.toJson(message);
            commands.lpush(queueKey, jsonMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to offer message to Redis queue: " + queueKey, e);
        }
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }

        try {
            // 将超时时间转换为秒
            long timeoutSeconds = unit.toSeconds(timeout);
            
            // 使用BLPOP进行阻塞式弹出，支持超时
            KeyValue<String, String> result = commands.blpop(timeoutSeconds, queueKey);
            
            if (result == null) {
                // 超时或队列为空
                return null;
            }
            
            // result.getKey() 是 key，result.getValue() 是 value
            String jsonMessage = result.getValue();
            return gson.fromJson(jsonMessage, messageType);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to poll message from Redis queue: " + queueKey, e);
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        try {
            Long size = commands.llen(queueKey);
            return size != null ? size.intValue() : 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get size of Redis queue: " + queueKey, e);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 获取队列的Redis键名
     * 
     * @return Redis键名
     */
    public String getQueueKey() {
        return queueKey;
    }

    /**
     * 获取Redis客户端
     * 
     * @return Redis客户端
     */
    public RedisClient getRedisClient() {
        return redisClient;
    }

    /**
     * 获取Redis连接
     * 
     * @return Redis连接
     */
    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }

    /**
     * 关闭队列（关闭连接和客户端）
     */
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    /**
     * 检查队列是否已关闭
     * 
     * @return true如果队列已关闭
     */
    public boolean isClosed() {
        return connection == null || connection.isOpen() == false;
    }
} 