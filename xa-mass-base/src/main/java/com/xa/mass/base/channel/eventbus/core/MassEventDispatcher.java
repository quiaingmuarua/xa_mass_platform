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
 * 事件分发器，负责管理事件监听器和高效分发事件。
 * 支持注解扫描、类型继承、线程安全。
 */
public class MassEventDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MassEventDispatcher.class);
    /**
     * 事件类型到处理器映射表，支持快速查找
     * 使用ConcurrentHashMap保证线程安全
     */
    private final Map<Class<?>, List<HandlerWrapper>> handlerMap = new ConcurrentHashMap<>();
    /**
     * 所有注册的处理器列表，用于支持事件继承
     * 使用CopyOnWriteArrayList保证读操作的高性能和线程安全
     */
    private final List<HandlerWrapper> allHandlers = new CopyOnWriteArrayList<>();

    /**
     * 注册事件监听器
     * @param listener 监听器对象
     */
    public void registerListener(Object listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        // 扫描监听器中所有带有@MassSubscribe注解的方法
        for (Method method : listener.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(MassSubscribe.class)) {
                Class<?>[] params = method.getParameterTypes();
                // 验证方法签名：必须有且仅有一个参数
                if (params.length == 1) {
                    Class<?> eventType = params[0];
                    HandlerWrapper wrapper = new HandlerWrapper(listener, method, eventType);
                    handlerMap.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(wrapper);
                    allHandlers.add(wrapper);
                } else {
                    log.warn("Method {} in {} has @MassSubscribe but invalid parameters (expected 1, got {})", method.getName(), listener.getClass().getSimpleName(), params.length);
                }
            }
        }
    }

    /**
     * 注销事件监听器
     * @param listener 监听器对象
     */
    public void unregisterListener(Object listener) {
        if (listener == null) {
            return;
        }
        allHandlers.removeIf(wrapper -> wrapper.getTarget() == listener);
        handlerMap.entrySet().removeIf(entry -> {
            List<HandlerWrapper> handlers = entry.getValue();
            handlers.removeIf(wrapper -> wrapper.getTarget() == listener);
            return handlers.isEmpty();
        });
    }

    /**
     * 分发事件到所有匹配的处理器
     * @param event 事件对象
     */
    public void dispatch(Object event) {
        if (event == null) {
            return;
        }
        Class<?> eventClass = event.getClass();
        // 首先尝试精确匹配
        List<HandlerWrapper> exactHandlers = handlerMap.get(eventClass);
        if (exactHandlers != null && !exactHandlers.isEmpty()) {
            for (HandlerWrapper handler : exactHandlers) {
                invokeHandler(handler, event);
            }
        }
        // 然后查找支持继承的处理器（避免重复调用精确匹配的处理器）
        for (HandlerWrapper handler : allHandlers) {
            if (exactHandlers != null && exactHandlers.contains(handler)) {
                continue;
            }
            if (handler.canHandle(eventClass)) {
                invokeHandler(handler, event);
            }
        }
    }

    /**
     * 安全调用事件处理器
     * @param handler 处理器
     * @param event 事件对象
     */
    private void invokeHandler(HandlerWrapper handler, Object event) {
        try {
            handler.invoke(event);
        } catch (Exception e) {
            log.error("Error invoking event handler: {} for event: {}", handler, event.getClass().getSimpleName(), e);
        }
    }

    /**
     * 获取指定事件类型的处理器数量
     * @param eventType 事件类型
     * @return 处理器数量
     */
    public int getHandlerCount(Class<?> eventType) {
        List<HandlerWrapper> handlers = handlerMap.get(eventType);
        return handlers != null ? handlers.size() : 0;
    }

    /**
     * 获取所有注册的处理器总数
     * @return 处理器总数
     */
    public int getTotalHandlerCount() {
        return allHandlers.size();
    }

    /**
     * 清除所有注册的处理器
     */
    public void clear() {
        handlerMap.clear();
        allHandlers.clear();
    }

    /**
     * 获取所有注册的事件类型
     * @return 事件类型列表
     */
    public List<Class<?>> getRegisteredEventTypes() {
        return new ArrayList<>(handlerMap.keySet());
    }
} 