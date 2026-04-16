package com.xa.mass.base.channel.eventbus.core;

import java.util.function.Consumer;

/**
 * Event bus facade with both direct handler registration and listener-instance registration.
 *
 * @param <T> base event type accepted by the bus
 */
public interface EventBusFacade<T> {
    <E extends T> void register(Class<E> eventType, Consumer<E> handler);

    <E extends T> void unregister(Class<E> eventType, Consumer<E> handler);

    <E extends T> void post(E event);

    void shutdown();

    default void register(Object listener) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default void unregister(Object listener) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
