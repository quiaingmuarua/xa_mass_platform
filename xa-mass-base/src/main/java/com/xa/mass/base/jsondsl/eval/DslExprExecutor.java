package com.xa.mass.base.jsondsl.eval;

import java.util.Map;

public class DslExprExecutor {

    /**
     * 执行 $EXPR 字段
     * @param exprObj 可以是 String 或 Map<String, Object>（带 lang 字段）
     */
    public static Object execute(Object exprObj, Map<String, Object> context) throws Exception {
        if (exprObj instanceof String) {
            // 默认使用 QL 表达式
            ExpressionEngine engine = ExpressionEngineRegistry.get("ql");
            return engine.eval((String) exprObj, context);
        } else if (exprObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exprMap = (Map<String, Object>) exprObj;
            String expr = (String) exprMap.get("expr");
            String lang = (String) exprMap.getOrDefault("lang", "ql");
            ExpressionEngine engine = ExpressionEngineRegistry.get(lang);
            if (engine == null) {
                throw new IllegalArgumentException("Unsupported expression engine: " + lang);
            }
            return engine.eval(expr, context);
        } else {
            throw new IllegalArgumentException("$EXPR must be string or object");
        }
    }
}