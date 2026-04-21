package com.xa.mass.base.jsondsl.eval;

import java.util.Map;

public interface ExpressionEngine {
    Object eval(String expr, Map<String, Object> context) throws Exception;

    default CompiledExpression compile(String expr) throws Exception {
        return new CompiledExpression(expr, expr);
    }

    default Object eval(CompiledExpression compiledExpression, Map<String, Object> context) throws Exception {
        return eval(compiledExpression.expression(), context);
    }
}
