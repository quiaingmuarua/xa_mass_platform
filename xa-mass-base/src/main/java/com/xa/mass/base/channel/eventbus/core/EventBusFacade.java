package com.xa.mass.base.channel.eventbus.core;

import java.util.function.Consumer;

public interface EventBusFacade {
    <E extends MassEvent> void register(Class<E> eventType, Consumer<E> handler);

    <E extends MassEvent> void unregister(Class<E> eventType, Consumer<E> handler);

    <E extends MassEvent> void post(E event);

    void shutdown();

    // 新增直接注册/注销listener实例的方法
    default void register(Object listener) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default void unregister(Object listener) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
