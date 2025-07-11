package com.xa.mass.base.channel.queue.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.TimeUnit;

/**
 * 基于Lettuce的Redis消息队列实现（复用全局连接）
 */
public class LettuceRedisQueue<T> implements MessageQueue<T> {
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String queueKey;
    private final String name;
    private final Gson gson;
    private final Class<T> messageType;

    /**
     * 推荐：只用queueKey区分队列，所有实例复用全局连接
     */
    public LettuceRedisQueue(String queueKey, String name, Class<T> messageType) {
        this.connection = RedisConnectionManager.getConnection();
        this.commands = connection.sync();
        this.queueKey = queueKey+"::queue";
        this.name = name+"::queue";
        this.messageType = messageType;
        this.gson = new GsonBuilder().create();
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
            long timeoutSeconds = unit.toSeconds(timeout);
            KeyValue<String, String> result = commands.blpop(timeoutSeconds, queueKey);
            if (result == null) {
                return null;
            }
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

    public String getQueueKey() {
        return queueKey;
    }
} 