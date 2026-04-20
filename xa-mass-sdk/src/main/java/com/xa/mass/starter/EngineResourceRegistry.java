package com.xa.mass.starter;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple registry for runtime-layer engine resources.
 */
public class EngineResourceRegistry {
    private final Map<Class<?>, Object> resources = new HashMap<>();

    /**
     * Register a resource instance by type.
     */
    public <T> void register(Class<T> type, T instance) {
        resources.put(type, instance);
    }

    /**
     * Look up a resource by type.
     */
    public <T> T get(Class<T> type) {
        return type.cast(resources.get(type));
    }

    /**
     * Return all registered resources.
     */
    public Collection<Object> allResources() {
        return resources.values();
    }
}
