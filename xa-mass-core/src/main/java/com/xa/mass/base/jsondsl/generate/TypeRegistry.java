package com.xa.mass.base.jsondsl.generate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy name-to-class registry for mock-data JSON-DSL generation.
 *
 * <p>This registry supports the old generator used by mock/dev fixtures. Do
 * not use it for task-to-worker matching rules.
 *
 * @deprecated Prefer typed JSON-DSL definitions for generic processing and
 * engine rule DSL for worker matching.
 */
@Deprecated(since = "2.0.0", forRemoval = false)
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
