package com.xa.mass.base.jsondsl.builtin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import com.xa.mass.base.jsondsl.util.FieldRuleEvaluator;

/**
 * 递归解析字段值，支持内置函数、Map、List、普通值。
 *
 * 新标准提供更好的类型安全、验证和扩展性，支持更丰富的表达式引擎和内置函数。
 */
public class TemplateValueResolver {
    // 移除 BUILTIN_RESOLVERS，全部走 OperatorRegistry

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
            // 只处理内置函数
            if (isBuiltinFunction(map)) {
                String funcKey = (String) map.keySet().iterator().next();
                // $EXPR 表达式支持
                if ("$EXPR".equals(funcKey)) {
                    Object exprObj = map.get(funcKey);
                    // 合并当前作用域和父作用域的所有变量
                    Map<String, Object> vars = new HashMap<>();
                    DslContext ctx = context;
                    while (ctx != null) {
                        vars.putAll(ctx.getVariables());
                        ctx = ctx.getParent();
                    }
                    try {
                        return com.xa.mass.base.jsondsl.eval.DslExprExecutor.execute(exprObj, vars);
                    } catch (Exception e) {
                        throw new JsonDslException("$EXPR 执行失败: " + exprObj, e);
                    }
                }
                Object param = map.get(funcKey);
                var func = OperatorRegistry.getFunction(funcKey);
//                System.out.println("[TemplateValueResolver] funcKey=" + funcKey + ", found=" + (func != null));
                if (func != null) {
                    // 递归解析参数：如果 param 是 List，则对每个元素递归 resolve
                    Object resolvedParam;
                    if (param instanceof List<?> list) {
                        resolvedParam = list.stream().map(v -> resolve(v, context)).toList();
                    } else {
                        resolvedParam = resolve(param, context);
                    }
                    Object[] args = resolvedParam instanceof List<?> l ? l.toArray() : new Object[]{resolvedParam};
                    return func.apply(args, context);
                }
                // fallback: 直接返回未处理的 map
                return map;
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
            if (str.startsWith("&.")) {
                String scopeName = context.getScopeName();
                if (scopeName != null) {
                    String realKey = "&" + scopeName + str.substring(1); // 变成 &Device.index
                    Object v = context.getVariable(realKey);
                    if (v != null) return v;
                }
                // fallback: 继续递归查找父作用域
            }
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

    private static boolean isFieldRule(Map<?, ?> map) {
        if (map.isEmpty()) return false;
        for (Object key : map.keySet()) {
            if (!(key instanceof String) || !((String) key).startsWith("$")) {
                return false;
            }
        }
        return true;
    }
} 