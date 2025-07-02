package com.xa.mass.base.eventbus.core;


import com.google.common.eventbus.AsyncEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GuavaEventBusFacade implements EventBusFacade {
    private final AsyncEventBus eventBus;
    private final ExecutorService executor;
    private final Map<Class<?>, List<Object>> listenerWrappers = new ConcurrentHashMap<>();
    private final Map<Object, Consumer<?>> consumerMapping = new ConcurrentHashMap<>();

    public GuavaEventBusFacade(int threadPoolSize) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
        this.eventBus = new AsyncEventBus(executor);
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, Consumer<E> handler) {
        Object wrapper = new Object() {
            @com.google.common.eventbus.Subscribe
            public void onEvent(E event) { handler.accept(event); }
        };
        eventBus.register(wrapper);
        listenerWrappers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(wrapper);
        consumerMapping.put(wrapper, handler);
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, Consumer<E> handler) {
        List<Object> wrappers = listenerWrappers.get(eventType);
        if (wrappers != null) {
            wrappers.removeIf(wrapper -> {
                Consumer<?> c = consumerMapping.get(wrapper);
                if (c == handler) {
                    eventBus.unregister(wrapper);
                    consumerMapping.remove(wrapper);
                    return true;
                }
                return false;
            });
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
