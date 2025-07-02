package com.xa.mass.base.mock;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 负责解析字段中的内置函数表达式，如 {"$CHOICE": [...]}, {"$UUID": true}，递归解析 Map/List。
 */
public class TemplateValueResolver {
    /**
     * 递归解析字段值，支持内置函数、Map、List、普通值。
     * @param value 字段值（Object）
     * @param context 上下文变量（如 i, j 等）
     * @return mock 后的值
     */
    public static Object resolve(Object value, Map<String, Object> context) {
        if (value == null) return null;
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            // 检查是否为内置函数表达式
            if (map.size() == 1) {
                Object key = map.keySet().iterator().next();
                if (key instanceof String && ((String) key).startsWith("$")) {
                    Object param = map.get(key);
                    // $JOIN 递归 resolve 每个元素
                    if ("$JOIN".equals(key) && param instanceof List) {
                        List<?> parts = (List<?>) param;
                        List<Object> resolved = parts.stream().map(p -> resolve(p, context)).collect(Collectors.toList());
                        return BuiltinFunctions.eval("$JOIN", resolved);
                    }
                    return BuiltinFunctions.eval((String) key, resolve(param, context));
                }
            }
            // 普通 Map，递归解析每个字段
            return map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    e -> e.getKey(),
                    e -> resolve(e.getValue(), context)
            ));
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            return list.stream().map(v -> resolve(v, context)).toList();
        } else if (value instanceof String) {
            // 支持 context 变量替换
            String str = (String) value;
            if (context != null && context.containsKey(str)) {
                Object v = context.get(str);
                return v == null ? null : v;
            }
            return str;
        } else {
            return value;
        }
    }
} 