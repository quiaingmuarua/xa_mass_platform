package com.xa.mass.base.old.eventbus.core;

import com.google.common.eventbus.AsyncEventBus;
import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.MassEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Deprecated
/**
 * 已过时：请优先使用StreamEventBusFacade + MessageStream实现。
 * 本地事件总线建议用InMemoryMessageStream，分布式用LettuceRedisStream。
 */
public class GuavaEventBusFacade implements EventBusFacade {
    private final AsyncEventBus eventBus;
    private final ExecutorService executor;
    private final Map<Class<?>, List<Object>> listenerWrappers = new ConcurrentHashMap<>();

    public GuavaEventBusFacade(int threadPoolSize) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
        this.eventBus = new AsyncEventBus(executor);
    }

    public void register(Object listener) {
        eventBus.register(listener);
        listenerWrappers.computeIfAbsent(listener.getClass(), k -> new ArrayList<>()).add(listener);
    }

    public void unregister(Object listener) {
        eventBus.unregister(listener);
        List<Object> wrappers = listenerWrappers.get(listener.getClass());
        if (wrappers != null) {
            wrappers.remove(listener);
        }
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注册带有@Subscribe注解的listener实例");
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注销带有@Subscribe注解的listener实例");
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
