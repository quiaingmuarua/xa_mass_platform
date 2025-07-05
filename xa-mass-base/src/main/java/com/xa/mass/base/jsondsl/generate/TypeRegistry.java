package com.xa.mass.base.jsondsl.generate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mock类型注册表，支持 name -> className 的注册和查找。
 */
public class TypeRegistry {
    private static final Map<String, String> registry = new ConcurrentHashMap<>();

    public static void register(String name, String className) {
        registry.put(name, className);
    }

    public static void register(String name, Class<?> clazz) {
        registry.put(name, clazz.getName());
    }

    public static String getClassName(String name) {
        return registry.get(name);
    }

    public static boolean contains(String name) {
        return registry.containsKey(name);
    }

    public static void clear() {
        registry.clear();
    }
} 