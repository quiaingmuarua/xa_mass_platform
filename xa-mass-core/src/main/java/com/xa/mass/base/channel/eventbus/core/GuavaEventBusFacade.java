package com.xa.mass.base.channel.eventbus.core;

import com.google.common.eventbus.AsyncEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GuavaEventBusFacade implements EventBusFacade<MassEvent> {
    private final AsyncEventBus eventBus;
    private final ExecutorService executor;
    private final Map<Class<?>, List<Object>> listenerWrappers = new ConcurrentHashMap<>();

    public GuavaEventBusFacade(int threadPoolSize) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
        this.eventBus = new AsyncEventBus(executor);
    }

    @Override
    public void register(Object listener) {
        eventBus.register(listener);
    }

    @Override
    public void unregister(Object listener) {
        eventBus.unregister(listener);
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, Consumer<E> handler) {
        Object wrapper = new Object() {
            @com.google.common.eventbus.Subscribe
            public void handle(E event) {
                if (eventType.isAssignableFrom(event.getClass())) {
                    handler.accept(event);
                }
            }
        };

        listenerWrappers.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(wrapper);
        eventBus.register(wrapper);
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, Consumer<E> handler) {
        List<Object> wrappers = listenerWrappers.get(eventType);
        if (wrappers != null) {
            wrappers.forEach(eventBus::unregister);
            wrappers.clear();
        }
    }

    @Override
    public <E extends MassEvent> void post(E event) {
        eventBus.post(event);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }
}
