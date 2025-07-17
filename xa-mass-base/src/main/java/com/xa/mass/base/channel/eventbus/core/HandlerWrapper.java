package com.xa.mass.base.channel.eventbus.core;

import java.lang.reflect.Method;

/**
 * 事件处理器包装类，缓存反射信息以提升性能
 * 支持泛型，可以处理任意类型的事件对象
 */
public record HandlerWrapper<T>(Object target, Method method, Class<?> eventType) {
    public HandlerWrapper(Object target, Method method, Class<?> eventType) {
        this.target = target;
        this.method = method;
        this.eventType = eventType;

        // 预设置方法访问权限，避免运行时重复检查
        if (!method.canAccess(target)) {
            method.setAccessible(true);
        }
    }

    /**
     * 调用事件处理方法
     * @param event 事件对象
     * @throws Exception 调用异常
     */
    public void invoke(T event) throws Exception {
        method.invoke(target, event);
    }
} 