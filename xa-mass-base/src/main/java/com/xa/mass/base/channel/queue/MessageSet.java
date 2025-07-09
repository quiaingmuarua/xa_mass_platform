package com.xa.mass.base.channel.queue;

/**
 * 通用消息集合接口，支持唯一元素存储
 * @param <T> 元素类型
 */
public interface MessageSet<T> {
    boolean add(T value);
    boolean remove(T value);
    boolean contains(T value);
    int size();
    String getName();
} 