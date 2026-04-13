package com.xa.mass.base.jsondsl.generate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mock类型注册表，支持 name -> className 的注册和查找。
 *
 * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition}
 * 和 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 进行 DSL 定义和解析。
 * 新标准支持更丰富的类型管理和验证机制。
 */
@Deprecated(since = "2.0.0", forRemoval = true)
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