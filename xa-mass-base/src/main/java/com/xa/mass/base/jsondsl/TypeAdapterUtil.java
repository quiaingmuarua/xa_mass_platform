package com.xa.mass.base.jsondsl;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

class TypeAdapterUtil {
    // 类型适配器注册表
    static final Map<Class<?>, Function<Object, Object>> TYPE_ADAPTERS = new HashMap<>();
    static {
        TYPE_ADAPTERS.put(LocalDateTime.class, v -> {
            if (v == null) return null;
            if (v instanceof LocalDateTime ldt) return ldt;
            if (v instanceof String str) {
                String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "HH:mm:ss", "HH:mm"};
                for (String fmt : formats) {
                    try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
                        if (fmt.length() == str.length()) {
                            return LocalDateTime.parse(str, formatter);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return null;
        });
        TYPE_ADAPTERS.put(Date.class, v -> {
            if (v == null) return null;
            if (v instanceof Date d) return d;
            if (v instanceof LocalDateTime ldt) return java.sql.Timestamp.valueOf(ldt);
            if (v instanceof String str) {
                String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "HH:mm:ss", "HH:mm"};
                for (String fmt : formats) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
                        if (fmt.length() == str.length()) {
                            return sdf.parse(str);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return null;
        });
        TYPE_ADAPTERS.put(String.class, v -> {
            if (v == null) return null;
            if (v instanceof Date date) return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
            if (v instanceof LocalDateTime ldt) return ldt.toString();
            return v.toString();
        });
        // 其他类型可按需扩展
    }

    static Object getPrimitiveDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    @SuppressWarnings("unchecked")
    static Object adaptType(Class<?> fieldType, Object value) {
        if (value == null) {
            if (fieldType.isPrimitive()) return getPrimitiveDefaultValue(fieldType);
            return null;
        }
        // 枚举类型适配
        if (fieldType.isEnum()) {
            if (value instanceof String strVal) {
                return Enum.valueOf((Class<Enum>) fieldType, strVal);
            }
            // 兼容直接传枚举对象
            if (fieldType.isInstance(value)) {
                return value;
            }
            // 兼容数字下标
            if (value instanceof Number num) {
                Object[] enumConstants = fieldType.getEnumConstants();
                int idx = num.intValue();
                if (idx >= 0 && idx < enumConstants.length) {
                    return enumConstants[idx];
                }
            }
            throw new JsonDslException("无法将 " + value + " 转为枚举 " + fieldType.getName());
        }
        // 类型适配器
        Function<Object, Object> adapter = TYPE_ADAPTERS.get(fieldType);
        if (adapter != null) {
            Object adapted = adapter.apply(value);
            if (adapted != null) return adapted;
        }
        return value;
    }
} 