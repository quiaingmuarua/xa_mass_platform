package com.xa.mass.base.channel.queue.memory;

import com.xa.mass.base.channel.queue.api.MessageMap;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于内存的消息映射实现，线程安全
 * 注意：不支持 null key 和 null value
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class InMemoryMessageMap<K, V> implements MessageMap<K, V> {
    private final ConcurrentMap<K, V> map = new ConcurrentHashMap<>();
    private final String name;

    public InMemoryMessageMap() {
        this("InMemoryMessageMap");
    }

    public InMemoryMessageMap(String name) {
        this.name = name;
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "Null keys are not supported");
        Objects.requireNonNull(value, "Null values are not supported");
        map.put(key, value);
    }

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        return map.get(key);
    }

    @Override
    public V remove(K key) {
        Objects.requireNonNull(key, "Null keys are not supported");
        return map.remove(key);
    }

    @Override
    public boolean containsKey(K key) {
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