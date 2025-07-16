package com.xa.mass.base.channel.eventbus.core;

import java.lang.reflect.Method;

/**
 * 事件处理器包装类，缓存反射信息以提升性能
 */
public record HandlerWrapper(Object target, Method method, Class<?> eventType) {
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
    public void invoke(Object event) throws Exception {
        method.invoke(target, event);
    }

    /**
     * 检查是否可以处理指定类型的事件
     * @param eventClass 事件类型
     * @return 是否可以处理
     */
    public boolean canHandle(Class<?> eventClass) {
        return eventType.isAssignableFrom(eventClass);
    }
} 