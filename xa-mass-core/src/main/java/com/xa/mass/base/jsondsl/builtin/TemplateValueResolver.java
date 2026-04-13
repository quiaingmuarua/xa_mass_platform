package com.xa.mass.base.jsondsl.builtin;

import com.xa.mass.base.jsondsl.eval.DslExprExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模板值解析器 - 主入口类
 *
 * 新标准提供更好的类型安全、验证和扩展性，支持更丰富的表达式引擎和内置函数。
 * 按规则类型拆分为多个专门的解析器，提高代码的可维护性和扩展性。
 */
public class TemplateValueResolver {

    /**
     * 主解析入口 - 根据规则类型分发到对应的解析器
     * @param rule 字段值
     * @param context DslContext 上下文变量（支持多级作用域）
     * @return 解析后的值
     */
    public static Object resolve(Object rule, DslContext context) {
        if (rule == null) {
            return null;
        }

        // 根据规则类型分发到对应的解析器
        if (rule instanceof Map<?, ?> map) {
            return MapRuleResolver.resolve(map, context);
        }

        if (rule instanceof List<?> list) {
            return ListRuleResolver.resolve(list, context);
        }

        if (rule instanceof String str) {
            return StringRuleResolver.resolve(str, context);
        }

        // 其他类型直接返回
        return rule;
    }

    /**
     * Map 规则解析器 - 处理内置函数和普通 Map
     */
    static class MapRuleResolver {

        public static Object resolve(Map<?, ?> map, DslContext context) {
            // 处理内置函数
            if (isBuiltinFunction(map)) {
                return BuiltinFunctionResolver.resolve(map, context);
            }

            // 普通 Map，递归解析每个字段
            return map.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey(),
                            e -> TemplateValueResolver.resolve(e.getValue(), context)
                    ));
        }

        private static boolean isBuiltinFunction(Map<?, ?> map) {
            if (map.size() != 1) return false;
            Object key = map.keySet().iterator().next();
            return key instanceof String && ((String) key).startsWith("$");
        }
    }

    /**
     * 内置函数解析器 - 专门处理 $ 开头的内置函数
     */
    static class BuiltinFunctionResolver {

        public static Object resolve(Map<?, ?> map, DslContext context) {
            String funcKey = (String) map.keySet().iterator().next();
            Object param = map.get(funcKey);

            // $EXPR 表达式支持
            if ("$EXPR".equalsIgnoreCase(funcKey)) {
                return ExprRuleResolver.resolve(param, context);
            }

            // 特殊处理 $CONTEXT 函数
            if ("$CONTEXT".equals(funcKey)) {
                return ContextRuleResolver.resolve(param, context);
            }

            // 其他函数直接使用 BuiltinFunctions.eval
            return StandardFunctionResolver.resolve(funcKey, param, context);
        }
    }

    /**
     * 表达式规则解析器 - 专门处理 $EXPR 表达式
     */
    static class ExprRuleResolver {

        public static Object resolve(Object exprObj, DslContext context) {
            // 合并当前作用域和父作用域的所有变量
            Map<String, Object> qlContext = new HashMap<>();
            DslContext ctx = context;
            while (ctx != null) {
                qlContext.putAll(ctx.getVariables());
                ctx = ctx.getParent();
            }

            try {
                return DslExprExecutor.execute(exprObj, qlContext);
            } catch (Exception e) {
                throw new JsonDslException("$EXPR 执行失败: " + exprObj, e);
            }
        }
    }

    /**
     * 上下文规则解析器 - 专门处理 $CONTEXT 函数
     */
    static class ContextRuleResolver {

        public static Object resolve(Object param, DslContext context) {
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
    }

    /**
     * 标准函数解析器 - 处理其他内置函数
     */
    static class StandardFunctionResolver {

        public static Object resolve(String funcKey, Object param, DslContext context) {
            Map<String, Object> evalParams = new HashMap<>();
            evalParams.put("curFiledVal", context.getVariable("curFiledVal"));
            evalParams.put("param", TemplateValueResolver.resolve(param, context));
            return BuiltinFunctions.eval(funcKey, evalParams);
        }
    }

    /**
     * List 规则解析器 - 处理列表类型规则
     */
    static class ListRuleResolver {

        public static Object resolve(List<?> list, DslContext context) {
            return list.stream()
                    .map(v -> TemplateValueResolver.resolve(v, context))
                    .toList();
        }
    }

    /**
     * 字符串规则解析器 - 处理字符串类型规则
     */
    static class StringRuleResolver {

        public static Object resolve(String str, DslContext context) {
            // 处理作用域变量 &Device.index
            if (str.startsWith("&.")) {
                return ScopeVariableResolver.resolve(str, context);
            }

            // 处理普通变量 &
            if (str.startsWith("&")) {
                return VariableResolver.resolve(str, context);
            }

            // 处理函数调用 $
            if (str.startsWith("$")) {
                return FunctionCallResolver.resolve(str, context);
            }

            // 普通字符串直接返回
            return str;
        }
    }

    /**
     * 作用域变量解析器 - 处理 &Device.index 格式的变量
     */
    static class ScopeVariableResolver {

        public static Object resolve(String str, DslContext context) {
            String scopeName = context.getScopeName();
            if (scopeName != null) {
                String realKey = "&" + scopeName + str.substring(1); // 变成 &Device.index
                Object v = context.getVariable(realKey);
                if (v != null) return v;
            }
            // fallback: 继续递归查找父作用域
            return VariableResolver.resolve(str, context);
        }
    }

    /**
     * 变量解析器 - 处理 & 开头的变量
     */
    static class VariableResolver {

        public static Object resolve(String str, DslContext context) {
            Object v = context.getVariable(str);
            return v != null ? v : str;
        }
    }

    /**
     * 函数调用解析器 - 处理 $ 开头的函数调用
     */
    static class FunctionCallResolver {

        public static Object resolve(String str, DslContext context) {
            try {
                // 合并当前作用域和父作用域的所有变量
                Map<String, Object> vars = new HashMap<>();
                DslContext ctx = context;
                while (ctx != null) {
                    vars.putAll(ctx.getVariables());
                    ctx = ctx.getParent();
                }

                // 处理无参函数
                if (!str.contains("(") && !str.contains(")")) {
                    str = str + "()";
                }

                return DslExprExecutor.execute(str, vars);
            } catch (Exception e) {
                throw new JsonDslException("函数调用执行失败: " + str, e);
            }
        }
    }
} 