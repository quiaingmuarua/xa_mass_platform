package com.xa.mass.base.jsondsl.generate;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class FieldCacheUtil {
    // Field 缓存：Class -> (fieldName -> Field)
    private static final ConcurrentHashMap<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    static Field getFieldFromCache(Class<?> clazz, String fieldName) {
        Map<String, Field> map = FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> m = new HashMap<>();
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        m.put(f.getName(), f);
                    } catch (InaccessibleObjectException e) {
                        // 跳过无法访问的字段（如 java.util 包中的内部字段）
                        // 这些字段通常不应该被外部访问
                    }
                }
            }
            return m;
        });
        return map.get(fieldName);
    }
} 