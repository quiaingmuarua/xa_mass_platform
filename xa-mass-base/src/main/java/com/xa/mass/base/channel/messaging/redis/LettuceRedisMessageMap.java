package com.xa.mass.base.channel.messaging.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.messaging.api.MessageMap;
import com.xa.mass.base.tool.RedisConnectionManager;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisHashCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Map;

/**
 * 基于Lettuce的Redis消息映射实现，底层用Redis hash存储，支持泛型和Gson序列化
 */
public class LettuceRedisMessageMap< V> implements MessageMap<String, V> {
    private static final Logger log = LoggerFactory.getLogger(LettuceRedisMessageMap.class);

    private final RedisHashCommands<String, String> hashCommands;
    private final String redisKey;
    private final String name;
    private final Gson gson;
    private final Class<V> valueType;

    /**
     * 统一的构造方法
     * @param queueKey 映射键名
     * @param messageType 消息类型Class（对于Map，通常是value的类型）
     * @param extraParams 扩展参数（可选）
     */
    public LettuceRedisMessageMap(String queueKey, Class<V> messageType, Map<String, String> extraParams) {
        StatefulRedisConnection<String, String> connection = RedisConnectionManager.getConnection();
        this.hashCommands = connection.sync();
        this.name = queueKey + "::hash";
        this.redisKey = queueKey + "::hash";
        this.gson = new GsonBuilder().create();
        
        // 对于Map，key类型默认为String，value类型为传入的messageType
        Class keyType = String.class;
        this.valueType = messageType;
        
        log.debug("Created LettuceRedisMessageMap: queueKey={}, keyType={}, valueType={}", 
                 queueKey, keyType.getSimpleName(), valueType.getSimpleName());
    }

    /**
     * 简化的构造方法（向后兼容）
     * @param queueKey 映射键名
     * @param messageType 消息类型Class
     */
    public LettuceRedisMessageMap(String queueKey, Class<V> messageType) {
        this(queueKey, messageType, null);
    }

    @Override
    public void put(String key, V value) {
        Objects.requireNonNull(key, "Null keys are not supported");
        Objects.requireNonNull(value, "Null values are not supported");
        String keyStr = gson.toJson(key);
        String valueStr = gson.toJson(value);
        hashCommands.hset(redisKey, keyStr, valueStr);
    }

    @Override
    public V get(String key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        String keyStr = gson.toJson(key);
        String valueStr = hashCommands.hget(redisKey, keyStr);
        if (valueStr == null) return null;
        return gson.fromJson(valueStr, valueType);
    }

    @Override
    public V remove(String key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        V old = get(key);
        String keyStr = gson.toJson(key);
        hashCommands.hdel(redisKey, keyStr);
        return old;
    }

    @Override
    public boolean containsKey(String key) {
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