package com.xa.mass.base.channel.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于内存的消息映射实现，线程安全
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
        map.put(key, value);
    }

    @Override
    public V get(K key) {
        return map.get(key);
    }

    @Override
    public V remove(K key) {
        return map.remove(key);
    }

    @Override
    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public String getName() {
        return name;
    }
} 