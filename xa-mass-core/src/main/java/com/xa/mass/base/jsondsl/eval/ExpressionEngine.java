package com.xa.mass.base.jsondsl.eval;

import java.util.Map;

public interface ExpressionEngine {
    Object eval(String expr, Map<String, Object> context) throws Exception;
}