package com.xa.mass.base.channel.eventbus.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Wraps an annotated event handler method and exposes a fast MethodHandle-based invocation path.
 */
public record HandlerWrapper<T>(Object target, MethodHandle methodHandle, Method method, Class<?> eventType) {

    public HandlerWrapper(Object target, Method method, Class<?> eventType) {
        this(target, createMethodHandle(target, method), method, eventType);
    }

    private static MethodHandle createMethodHandle(Object target, Method method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "Event handler method must be public: " + method.getName()
                            + " in class " + target.getClass().getSimpleName());
        }

        if (!method.canAccess(target)) {
            throw new IllegalArgumentException(
                    "Cannot access method: " + method.getName()
                            + " in class " + target.getClass().getSimpleName()
                            + ". Ensure the method is public.");
        }

        try {
            return MethodHandles.lookup()
                    .unreflect(method)
                    .bindTo(target);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot create MethodHandle for: " + method.getName(), e);
        }
    }

    public void invoke(T event) throws Throwable {
        try {
            methodHandle.invoke(event);
        } catch (Throwable t) {
            throw new RuntimeException(
                    "Failed to invoke event handler: " + method.getName()
                            + " in class " + target.getClass().getSimpleName()
                            + " for event: " + event.getClass().getSimpleName(),
                    t);
        }
    }

    public String getDescription() {
        return target.getClass().getSimpleName() + "." + method.getName()
                + "(" + eventType.getSimpleName() + ")";
    }

    @Override
    public String toString() {
        return "HandlerWrapper{" + getDescription() + "}";
    }
}
