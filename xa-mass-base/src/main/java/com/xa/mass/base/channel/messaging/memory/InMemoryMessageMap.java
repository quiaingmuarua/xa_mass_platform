package com.xa.mass.base.channel.messaging.memory;

import com.xa.mass.base.channel.messaging.api.MessageMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于内存的消息映射实现，线程安全
 * 注意：不支持 null key 和 null value
 * @param <V> 值类型
 */
public class InMemoryMessageMap<V> implements MessageMap<String, V> {
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageMap.class);
    
    private final ConcurrentMap<String, V> map = new ConcurrentHashMap<>();
    private final String name;

    /**
     * 统一的构造方法
     * @param queueKey 映射键名
     * @param valueType value类型Class
     * @param extraParams 扩展参数（可选）
     */
    public InMemoryMessageMap(String queueKey, Class<V> valueType, Map<String, String> extraParams) {
        this.name = queueKey != null ? queueKey : "InMemoryMessageMap";
        log.debug("Created InMemoryMessageMap: name={}, valueType={}", name, valueType.getSimpleName());
    }

    /**
     * 简化的构造方法
     * @param queueKey 映射键名
     * @param valueType value类型Class
     */
    public InMemoryMessageMap(String queueKey, Class<V> valueType) {
        this(queueKey, valueType, java.util.Collections.emptyMap());
    }

    @Override
    public void put(String key, V value) {
        Objects.requireNonNull(key, "Null keys are not supported");
        Objects.requireNonNull(value, "Null values are not supported");
        map.put(key, value);
    }

    @Override
    public V get(String key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        return map.get(key);
    }

    @Override
    public V remove(String key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        return map.remove(key);
    }

    @Override
    public boolean containsKey(String key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        return map.containsKey(key);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Collection<V> values() {
        return map.values();
    }

    @Override
    public String getName() {
        return name;
    }
} 