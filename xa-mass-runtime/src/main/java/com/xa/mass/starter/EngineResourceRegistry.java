package com.xa.mass.starter;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 引擎资源注册表，starter 层唯一实现
 */
public class EngineResourceRegistry {
    private final Map<Class<?>, Object> resources = new HashMap<>();

    /**
     * 注册资源
     */
    public <T> void register(Class<T> type, T instance) {
        resources.put(type, instance);
    }

    /**
     * 获取资源
     */
    public <T> T get(Class<T> type) {
        return type.cast(resources.get(type));
    }

    /**
     * 获取所有资源
     */
    public Collection<Object> allResources() {
        return resources.values();
    }
} 