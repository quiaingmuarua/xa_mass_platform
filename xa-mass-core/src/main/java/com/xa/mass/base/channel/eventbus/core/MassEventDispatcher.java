package com.xa.mass.base.channel.eventbus.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dispatches events to registered listeners using exact event-type matching.
 */
public class MassEventDispatcher<T> {
    private static final Logger log = LoggerFactory.getLogger(MassEventDispatcher.class);

    private final Map<Class<?>, List<HandlerWrapper<T>>> handlerMap = new ConcurrentHashMap<>();

    public void registerListener(Object listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }

        for (Method method : listener.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(MassSubscribe.class)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1) {
                    Class<?> eventType = params[0];
                    HandlerWrapper<T> wrapper = new HandlerWrapper<>(listener, method, eventType);
                    handlerMap.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(wrapper);
                } else {
                    log.warn("Method {} in {} has @MassSubscribe but invalid parameters (expected 1, got {})",
                            method.getName(), listener.getClass().getSimpleName(), params.length);
                }
            }
        }
    }

    public void unregisterListener(Object listener) {
        if (listener == null) {
            return;
        }
        handlerMap.entrySet().removeIf(entry -> {
            List<HandlerWrapper<T>> handlers = entry.getValue();
            handlers.removeIf(wrapper -> wrapper.target() == listener);
            return handlers.isEmpty();
        });
    }

    public void dispatch(T event) {
        if (event == null) {
            return;
        }

        List<HandlerWrapper<T>> exactHandlers = handlerMap.get(event.getClass());
        if (exactHandlers != null && !exactHandlers.isEmpty()) {
            RuntimeException firstException = null;
            for (HandlerWrapper<T> handler : exactHandlers) {
                try {
                    invokeHandler(handler, event);
                } catch (Exception e) {
                    if (firstException == null) {
                        firstException = (e instanceof RuntimeException)
                                ? (RuntimeException) e
                                : new RuntimeException(e);
                    }
                    log.error("Unexpected error in event handler invocation", e);
                }
            }
            if (firstException != null) {
                throw firstException;
            }
        }
    }

    private void invokeHandler(HandlerWrapper<T> handler, T event) {
        try {
            handler.invoke(event);
        } catch (Throwable e) {
            log.error("Error invoking event handler: {} for event: {}",
                    handler.getDescription(), event.getClass().getSimpleName(), e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    public int getHandlerCount(Class<?> eventType) {
        List<HandlerWrapper<T>> handlers = handlerMap.get(eventType);
        return handlers != null ? handlers.size() : 0;
    }

    public int getTotalHandlerCount() {
        int total = 0;
        for (List<HandlerWrapper<T>> handlers : handlerMap.values()) {
            total += handlers.size();
        }
        return total;
    }

    public void clear() {
        handlerMap.clear();
    }

    public List<Class<?>> getRegisteredEventTypes() {
        return new ArrayList<>(handlerMap.keySet());
    }
}
