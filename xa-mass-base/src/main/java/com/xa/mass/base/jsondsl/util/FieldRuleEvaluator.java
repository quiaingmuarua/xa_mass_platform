package com.xa.mass.base.jsondsl.util;

import com.xa.mass.base.jsondsl.builtin.OperatorRegistry;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import java.util.*;
import java.util.function.BiFunction;

public class FieldRuleEvaluator {
    static {
        // 注册基础操作符到 OperatorRegistry
        OperatorRegistry.registerFunction("$eq", (args, ctx) -> Objects.equals(args[0], args[1]));
        OperatorRegistry.registerFunction("$ne", (args, ctx) -> !Objects.equals(args[0], args[1]));
        OperatorRegistry.registerFunction("$gt", (args, ctx) -> compareNumber(args[0], args[1], 1));
        OperatorRegistry.registerFunction("$gte", (args, ctx) -> compareNumber(args[0], args[1], 0, 1));
        OperatorRegistry.registerFunction("$lt", (args, ctx) -> compareNumber(args[0], args[1], -1));
        OperatorRegistry.registerFunction("$lte", (args, ctx) -> compareNumber(args[0], args[1], 0, -1));
        OperatorRegistry.registerFunction("$in", (args, ctx) -> (args[1] instanceof Collection<?>) && args[0] != null && ((Collection<?>) args[1]).contains(args[0]));
        OperatorRegistry.registerFunction("$choice", (args, ctx) -> true); // 生成时用，过滤时总是通过
    }

    public static boolean evaluate(Object fieldValue, Map<String, Object> rule) {
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String op = entry.getKey();
            Object val = entry.getValue();
            if (op == null || !op.startsWith("$")) continue;
            BiFunction<Object[], DslContext, Object> func = OperatorRegistry.getFunction(op);
            if (func == null) throw new UnsupportedOperationException("不支持的操作符: " + op);
            Object result = func.apply(new Object[]{fieldValue, val}, null);
            if (!(result instanceof Boolean) || !((Boolean) result)) return false;
        }
        return true;
    }

    private static boolean compareNumber(Object fieldValue, Object val, int... validResults) {
        if (fieldValue == null || val == null) return false;
        try {
            double f = Double.parseDouble(fieldValue.toString());
            double v = Double.parseDouble(val.toString());
            int cmp = Double.compare(f, v);
            for (int r : validResults) {
                if (cmp == r) return true;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
} 