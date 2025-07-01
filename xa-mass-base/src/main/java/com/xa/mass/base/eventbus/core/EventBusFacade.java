package com.xa.mass.base.eventbus.core;

import java.util.function.Consumer;

public interface EventBusFacade {
    <E extends MassEvent> void register(Class<E> eventType, Consumer<E> handler);
    <E extends MassEvent> void unregister(Class<E> eventType, Consumer<E> handler);
    <E extends MassEvent> void post(E event);
    void shutdown();
}
