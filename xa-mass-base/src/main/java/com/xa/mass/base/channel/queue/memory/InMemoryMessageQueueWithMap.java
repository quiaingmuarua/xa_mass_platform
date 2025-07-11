package com.xa.mass.base.channel.queue.memory;

import com.xa.mass.base.channel.queue.api.MessageQueueWithMap;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 内存实现的双队列（队列+映射）消息存储
 * 注意：队列和映射部分都不支持 null
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class InMemoryMessageQueueWithMap<K, V> implements MessageQueueWithMap<K, V> {
    private final BlockingQueue<V> queue;
    private final ConcurrentMap<K, V> map;
    private final String name;

    public InMemoryMessageQueueWithMap() {
        this("InMemoryMessageQueueWithMap");
    }

    public InMemoryMessageQueueWithMap(String name) {
        this.queue = new LinkedBlockingQueue<>();
        this.map = new ConcurrentHashMap<>();
        this.name = name;
    }

    // MessageQueue 部分
    @Override
    public void offer(V message) {
        Objects.requireNonNull(message, "Null messages are not supported");
        queue.offer(message);
    }

    @Override
    public V poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public String getName() {
        return name;
    }

    // MessageMap 部分
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
    public java.util.Collection<V> values() {
        return map.values();
    }

    // Map 的 size 不一定等于队列 size，这里返回队列 size
    // 如需 map 的 size 可用 getMapSize()
    public int getMapSize() {
        return map.size();
    }
} 