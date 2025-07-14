package com.xa.mass.base.channel.queue.api;

import java.util.Collection;

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
    Collection<V> values();
    
    /**
     * 统一的构造方法接口
     * 所有实现类都应该提供这个构造方法
     * @param queueKey 映射键名
     * @param messageType 消息类型Class（对于Map，通常是value的类型）
     * @param extraParams 扩展参数（可选）
     */
    // 注意：接口中不能定义构造方法，这里只是文档说明
    // 所有实现类都应该提供：MessageMap(String queueKey, Class<V> messageType, Map<String, String> extraParams)
} 