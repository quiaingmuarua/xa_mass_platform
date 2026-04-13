package com.xa.mass.base.channel.eventbus.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 事件处理器包装类，使用MethodHandle优化性能并添加安全检查
 * 支持泛型，可以处理任意类型的事件对象
 */
public record HandlerWrapper<T>(Object target, MethodHandle methodHandle, Method method, Class<?> eventType) {
    
    public HandlerWrapper(Object target, Method method, Class<?> eventType) {
        this(target, createMethodHandle(target, method), method, eventType);
    }
    
    /**
     * 创建MethodHandle，包含安全检查
     */
    private static MethodHandle createMethodHandle(Object target, Method method) {
        // 🔒 安全检查：只允许public方法
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException(
                "Event handler method must be public: " + method.getName() + 
                " in class " + target.getClass().getSimpleName());
        }
        
        // 🔒 验证方法可访问性（不强制设置setAccessible）
        if (!method.canAccess(target)) {
            throw new IllegalArgumentException(
                "Cannot access method: " + method.getName() + 
                " in class " + target.getClass().getSimpleName() + 
                ". Ensure the method is public.");
        }
        
        try {
            // 使用MethodHandle替代反射，性能提升2-3倍
            return MethodHandles.lookup()
                .unreflect(method)
                .bindTo(target);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot create MethodHandle for: " + method.getName(), e);
        }
    }

    /**
     * 调用事件处理方法（使用高性能MethodHandle）
     * @param event 事件对象
     * @throws Throwable 调用异常
     */
    public void invoke(T event) throws Throwable {
        try {
            // 使用invoke而不是invokeExact，允许类型转换
            methodHandle.invoke(event);
        } catch (Throwable t) {
            // 包装异常信息，便于调试
            throw new RuntimeException(
                "Failed to invoke event handler: " + method.getName() + 
                " in class " + target.getClass().getSimpleName() + 
                " for event: " + event.getClass().getSimpleName(), t);
        }
    }
    
    /**
     * 获取处理器描述信息
     */
    public String getDescription() {
        return target.getClass().getSimpleName() + "." + method.getName() + 
               "(" + eventType.getSimpleName() + ")";
    }
    
    @Override
    public String toString() {
        return "HandlerWrapper{" + getDescription() + "}";
    }
} 