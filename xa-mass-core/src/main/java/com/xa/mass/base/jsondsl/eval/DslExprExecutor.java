package com.xa.mass.base.jsondsl.eval;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DslExprExecutor {

    private static final ConcurrentHashMap<String, CompiledExpression> COMPILED_CACHE = new ConcurrentHashMap<>();

    /**
     * Execute a $EXPR rule. The input may be either a raw string expression or
     * an object with {lang, expr}.
     */
    public static Object execute(Object exprObj, Map<String, Object> context) throws Exception {
        if (exprObj instanceof String exprString) {
            exprObj = Map.of("lang", "ql", "expr", exprString);
        }
        if (!(exprObj instanceof Map<?, ?> exprMapObject)) {
            throw new IllegalArgumentException("$EXPR must be string or object");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> exprMap = (Map<String, Object>) exprMapObject;
        String expr = (String) exprMap.get("expr");
        String lang = String.valueOf(exprMap.getOrDefault("lang", "ql"));

        ExpressionEngine engine = ExpressionEngineRegistry.get(lang);
        if (engine == null) {
            throw new IllegalArgumentException("Unsupported expression engine: " + lang);
        }

        return engine.eval(getOrCompile(engine, lang, expr), context);
    }

    static void clearCompiledCache() {
        COMPILED_CACHE.clear();
    }

    static int getCompiledCacheSize() {
        return COMPILED_CACHE.size();
    }

    private static CompiledExpression getOrCompile(ExpressionEngine engine, String lang, String expr) throws Exception {
        String normalizedExpr = normalizeExpression(expr);
        String cacheKey = lang.toLowerCase() + "::" + normalizedExpr;
        CompiledExpression cached = COMPILED_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        CompiledExpression compiled = engine.compile(normalizedExpr);
        CompiledExpression existing = COMPILED_CACHE.putIfAbsent(cacheKey, compiled);
        return existing != null ? existing : compiled;
    }

    private static String normalizeExpression(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new JsonDslException("Expression cannot be blank");
        }
        return expr.trim();
    }
}
