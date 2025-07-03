package com.xa.mass.base.jsondsl;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 递归解析字段值，支持内置函数、Map、List、普通值。
 */
public class TemplateValueResolver {
    // 内置函数处理器注册表
    private static final Map<BuiltinFunc, BiFunction<Object, DslContext, Object>> BUILTIN_RESOLVERS = new HashMap<>();
    static {
        BUILTIN_RESOLVERS.put(BuiltinFunc.JOIN, (param, ctx) -> {
            List<?> parts = (List<?>) param;
            List<Object> resolved = parts.stream().map(p -> resolve(p, ctx)).collect(Collectors.toList());
            return BuiltinFunctions.eval(BuiltinFunc.JOIN.key(), resolved);
        });
        BUILTIN_RESOLVERS.put(BuiltinFunc.CHOICE, (param, ctx) -> BuiltinFunctions.eval(BuiltinFunc.CHOICE.key(), resolve(param, ctx)));
        BUILTIN_RESOLVERS.put(BuiltinFunc.RANGE, (param, ctx) -> BuiltinFunctions.eval(BuiltinFunc.RANGE.key(), resolve(param, ctx)));
        BUILTIN_RESOLVERS.put(BuiltinFunc.UUID, (param, ctx) -> BuiltinFunctions.eval(BuiltinFunc.UUID.key(), resolve(param, ctx)));
        BUILTIN_RESOLVERS.put(BuiltinFunc.RANDOM, (param, ctx) -> BuiltinFunctions.eval(BuiltinFunc.RANDOM.key(), resolve(param, ctx)));
        BUILTIN_RESOLVERS.put(BuiltinFunc.CONTEXT, (param, ctx) -> getContextValue(param, ctx));
        BUILTIN_RESOLVERS.put(BuiltinFunc.NOW, (param, ctx) -> BuiltinFunctions.eval(BuiltinFunc.NOW.key(), resolve(param, ctx)));
        BUILTIN_RESOLVERS.put(BuiltinFunc.TIME_RANGE, (param, ctx) -> BuiltinFunctions.eval(BuiltinFunc.TIME_RANGE.key(), resolve(param, ctx)));
    }

    /**
     * 递归解析字段值，支持内置函数、Map、List、普通值。
     * @param value 字段值
     * @param context DslContext 上下文变量（支持多级作用域）
     * @return mock 后的值
     */
    public static Object resolve(Object value, DslContext context) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            if (isBuiltinFunction(map)) {
                String funcKey = (String) map.keySet().iterator().next();
                BuiltinFunc func = BuiltinFunc.fromKey(funcKey);
                Object param = map.get(funcKey);
                BiFunction<Object, DslContext, Object> resolver = BUILTIN_RESOLVERS.get(func);
                if (resolver != null) {
                    return resolver.apply(param, context);
                }
                // fallback: 直接用 BuiltinFunctions.eval
                return BuiltinFunctions.eval(funcKey, resolve(param, context));
            }
            // 普通 Map，递归解析每个字段
            return map.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey(),
                            e -> resolve(e.getValue(), context)
                    ));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> resolve(v, context)).toList();
        }
        if (value instanceof String str) {
            // 只对 & 开头的变量做作用域查找
            if (str.startsWith("&")) {
                Object v = context.getVariable(str);
                return v != null ? v : str;
            }
            // 只对 $ 开头的字符串做特殊处理（如 $NOW 等），其余直接返回
            return str;
        }
        return value;
    }

    /**
     * 从上下文中获取指定键的值（兼容 $CONTEXT 语法）
     * @param param 键名（字符串）或键名列表
     * @param context DslContext
     * @return 上下文中的值
     */
    private static Object getContextValue(Object param, DslContext context) {
        if (context == null) {
            return null;
        }
        if (param instanceof String) {
            return context.getVariable((String) param);
        } else if (param instanceof List<?>) {
            List<?> keys = (List<?>) param;
            for (Object key : keys) {
                if (key instanceof String) {
                    Object v = context.getVariable((String) key);
                    if (v != null) return v;
                }
            }
            return null;
        } else if (param == null) {
            return context.getVariable("&index");
        }
        return null;
    }

    private static boolean isBuiltinFunction(Map<?, ?> map) {
        if (map.size() != 1) return false;
        Object key = map.keySet().iterator().next();
        return key instanceof String && ((String) key).startsWith("$");
    }
} 