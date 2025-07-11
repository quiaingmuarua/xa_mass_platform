package com.xa.mass.base.channel.queue.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.queue.api.MessageMap;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisHashCommands;

import java.util.Objects;
import java.util.Collection;
import java.util.ArrayList;

/**
 * 基于Lettuce的Redis消息映射实现，底层用Redis hash存储，支持泛型和Gson序列化
 */
public class LettuceRedisMessageMap<K, V> implements MessageMap<K, V> {
    private final StatefulRedisConnection<String, String> connection;
    private final RedisHashCommands<String, String> hashCommands;
    private final String redisKey;
    private final String name;
    private final Gson gson;
    private final Class<K> keyType;
    private final Class<V> valueType;



    public LettuceRedisMessageMap(String name, Class<K> keyType, Class<V> valueType) {
        this.connection = RedisConnectionManager.getConnection();
        this.hashCommands = connection.sync();
        this.name = name+"::hash";
        this.redisKey = name+"::hash";
        this.gson = new GsonBuilder().create();
        this.keyType = keyType;
        this.valueType = valueType;
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "Null keys are not supported");
        Objects.requireNonNull(value, "Null values are not supported");
        String keyStr = gson.toJson(key);
        String valueStr = gson.toJson(value);
        hashCommands.hset(redisKey, keyStr, valueStr);
    }

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        String keyStr = gson.toJson(key);
        String valueStr = hashCommands.hget(redisKey, keyStr);
        if (valueStr == null) return null;
        return gson.fromJson(valueStr, valueType);
    }

    @Override
    public V remove(K key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        V old = get(key);
        String keyStr = gson.toJson(key);
        hashCommands.hdel(redisKey, keyStr);
        return old;
    }

    @Override
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        String keyStr = gson.toJson(key);
        return hashCommands.hexists(redisKey, keyStr);
    }

    @Override
    public int size() {
        Long len = hashCommands.hlen(redisKey);
        return len != null ? len.intValue() : 0;
    }

    @Override
    public Collection<V> values() {
        java.util.List<V> result = new ArrayList<>();
        for (String keyStr : hashCommands.hkeys(redisKey)) {
            String valueStr = hashCommands.hget(redisKey, keyStr);
            if (valueStr != null) {
                result.add(gson.fromJson(valueStr, valueType));
            }
        }
        return result;
    }

    @Override
    public String getName() {
        return name;
    }
} 