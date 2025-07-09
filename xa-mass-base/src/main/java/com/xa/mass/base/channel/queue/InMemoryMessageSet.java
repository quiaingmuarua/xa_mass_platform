package com.xa.mass.base.channel.queue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的消息集合实现，线程安全
 * @param <T> 元素类型
 */
public class InMemoryMessageSet<T> implements MessageSet<T> {
    private final Set<T> set;
    private final String name;

    public InMemoryMessageSet() {
        this("InMemoryMessageSet");
    }

    public InMemoryMessageSet(String name) {
        this.set = ConcurrentHashMap.newKeySet();
        this.name = name;
    }

    @Override
    public boolean add(T value) {
        return set.add(value);
    }

    @Override
    public boolean remove(T value) {
        return set.remove(value);
    }

    @Override
    public boolean contains(T value) {
        return set.contains(value);
    }

    @Override
    public int size() {
        return set.size();
    }

    @Override
    public String getName() {
        return name;
    }
} 