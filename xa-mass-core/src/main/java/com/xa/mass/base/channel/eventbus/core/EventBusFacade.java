package com.xa.mass.base.channel.eventbus.core;

import java.util.function.Consumer;

/**
 * 事件总线门面接口，支持泛型事件类型
 * @param <T> 事件的基础类型
 */
public interface EventBusFacade<T> {
    <E extends T> void register(Class<E> eventType, Consumer<E> handler);

    <E extends T> void unregister(Class<E> eventType, Consumer<E> handler);

    <E extends T> void post(E event);

    void shutdown();

    // 新增直接注册/注销listener实例的方法
    default void register(Object listener) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default void unregister(Object listener) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
