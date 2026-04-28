package com.xa.mass.base.jsondsl.builtin;

import com.xa.mass.base.jsondsl.eval.DslExprExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central rule resolver for JSON DSL values.
 *
 * <p>The canonical typed path prefers structured forms such as
 * {@code {"$EXPR": "age > 30"}}. String shorthand such as
 * {@code "$EXPR(age > 30)"} remains compatibility-only.
 */
public class TemplateValueResolver {

    public static Object resolve(Object rule, DslContext context) {
        if (rule == null) {
            return null;
        }

        if (rule instanceof Map<?, ?> map) {
            return MapRuleResolver.resolve(map, context);
        }

        if (rule instanceof List<?> list) {
            return ListRuleResolver.resolve(list, context);
        }

        if (rule instanceof String str) {
            return StringRuleResolver.resolve(str, context);
        }

        return rule;
    }

    static class MapRuleResolver {

        public static Object resolve(Map<?, ?> map, DslContext context) {
            if (isBuiltinFunction(map)) {
                return BuiltinFunctionResolver.resolve(map, context);
            }

            return map.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> TemplateValueResolver.resolve(entry.getValue(), context)
                    ));
        }

        private static boolean isBuiltinFunction(Map<?, ?> map) {
            if (map.size() != 1) {
                return false;
            }
            Object key = map.keySet().iterator().next();
            return key instanceof String stringKey && stringKey.startsWith("$");
        }
    }

    static class BuiltinFunctionResolver {

        public static Object resolve(Map<?, ?> map, DslContext context) {
            String funcKey = (String) map.keySet().iterator().next();
            Object param = map.get(funcKey);

            if ("$EXPR".equalsIgnoreCase(funcKey)) {
                return ExprRuleResolver.resolve(param, context);
            }

            if ("$CONTEXT".equals(funcKey)) {
                return ContextRuleResolver.resolve(param, context);
            }

            return StandardFunctionResolver.resolve(funcKey, param, context);
        }
    }

    static class ExprRuleResolver {

        public static Object resolve(Object exprObj, DslContext context) {
            Map<String, Object> exprContext = flattenContext(context);
            try {
                return DslExprExecutor.execute(exprObj, exprContext);
            } catch (Exception e) {
                throw new JsonDslException("$EXPR execution failed: " + exprObj, e);
            }
        }
    }

    static class ContextRuleResolver {

        public static Object resolve(Object param, DslContext context) {
            if (context == null) {
                return null;
            }

            if (param instanceof String key) {
                Object value = context.getVariable(key);
                if (value == null && context.isStrict()) {
                    throw new JsonDslException("Unresolved context variable: " + key);
                }
                return value;
            }

            if (param instanceof List<?> keys) {
                for (Object key : keys) {
                    if (key instanceof String stringKey) {
                        Object value = context.getVariable(stringKey);
                        if (value != null) {
                            return value;
                        }
                    }
                }
                if (context.isStrict()) {
                    throw new JsonDslException("Unresolved context variable candidates: " + keys);
                }
                return null;
            }

            if (param == null) {
                Object value = context.getVariable("&index");
                if (value == null && context.isStrict()) {
                    throw new JsonDslException("Unresolved context variable: &index");
                }
                return value;
            }

            return null;
        }
    }

    static class StandardFunctionResolver {

        public static Object resolve(String funcKey, Object param, DslContext context) {
            Map<String, Object> evalParams = new HashMap<>();
            evalParams.put("curFiledVal", context.getVariable("curFiledVal"));
            evalParams.put("param", TemplateValueResolver.resolve(param, context));
            return BuiltinFunctions.eval(funcKey, evalParams);
        }
    }

    static class ListRuleResolver {

        public static Object resolve(List<?> list, DslContext context) {
            return list.stream()
                    .map(item -> TemplateValueResolver.resolve(item, context))
                    .toList();
        }
    }

    static class StringRuleResolver {

        public static Object resolve(String str, DslContext context) {
            if (str.startsWith("&.")) {
                return ScopeVariableResolver.resolve(str, context);
            }

            if (str.startsWith("&")) {
                return VariableResolver.resolve(str, context);
            }

            if (str.startsWith("$")) {
                return FunctionCallResolver.resolve(str, context);
            }

            return str;
        }
    }

    static class ScopeVariableResolver {

        public static Object resolve(String str, DslContext context) {
            String scopeName = context.getScopeName();
            if (scopeName != null) {
                String realKey = "&" + scopeName + str.substring(1);
                Object value = context.getVariable(realKey);
                if (value != null) {
                    return value;
                }
            }
            return VariableResolver.resolve(str, context);
        }
    }

    static class VariableResolver {

        public static Object resolve(String str, DslContext context) {
            Object value = context.getVariable(str);
            if (value != null) {
                return value;
            }
            if (context != null && context.isStrict()) {
                throw new JsonDslException("Unresolved variable: " + str);
            }
            return str;
        }
    }

    static class FunctionCallResolver {

        public static Object resolve(String str, DslContext context) {
            try {
                if (isExprShorthand(str)) {
                    return ExprRuleResolver.resolve(extractExprBody(str), context);
                }

                Map<String, Object> vars = flattenContext(context);
                String expression = str;
                if (!expression.contains("(") && !expression.contains(")")) {
                    expression = expression + "()";
                }

                return DslExprExecutor.execute(expression, vars);
            } catch (Exception e) {
                throw new JsonDslException("Function call execution failed: " + str, e);
            }
        }

        private static boolean isExprShorthand(String str) {
            return str != null
                    && str.regionMatches(true, 0, "$EXPR(", 0, 6)
                    && str.endsWith(")");
        }

        private static String extractExprBody(String str) {
            return str.substring(6, str.length() - 1).trim();
        }
    }

    private static Map<String, Object> flattenContext(DslContext context) {
        Map<String, Object> vars = new HashMap<>();
        DslContext current = context;
        while (current != null) {
            vars.putAll(current.getVariables());
            current = current.getParent();
        }
        return vars;
    }
}
