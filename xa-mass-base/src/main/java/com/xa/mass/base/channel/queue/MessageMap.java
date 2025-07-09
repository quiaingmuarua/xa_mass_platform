package com.xa.mass.base.channel.queue;

/**
 * 通用消息映射接口，支持 key-value 消息存储
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface MessageMap<K, V> {
    void put(K key, V value);
    V get(K key);
    V remove(K key);
    boolean containsKey(K key);
    int size();
    String getName();
} 