package com.xa.mass.base.jsondsl.util;

import java.util.*;
import java.util.function.BiFunction;

public class FieldRuleEvaluator {
    // 统一操作符注册表，key 小写
    private static final Map<String, BiFunction<Object, Object, Boolean>> OPERATOR_MAP = new HashMap<>();

    static {
        // 注册基础操作符
        registerOperator("$eq", (fieldValue, val) -> Objects.equals(fieldValue, val));
        registerOperator("$ne", (fieldValue, val) -> !Objects.equals(fieldValue, val));
        registerOperator("$gt", (fieldValue, val) -> compareNumber(fieldValue, val, 1));
        registerOperator("$gte", (fieldValue, val) -> compareNumber(fieldValue, val, 0, 1));
        registerOperator("$lt", (fieldValue, val) -> compareNumber(fieldValue, val, -1));
        registerOperator("$lte", (fieldValue, val) -> compareNumber(fieldValue, val, 0, -1));
        registerOperator("$in", (fieldValue, val) -> (val instanceof Collection<?>) && fieldValue != null && ((Collection<?>) val).contains(fieldValue));
        registerOperator("$choice", (fieldValue, val) -> true); // 生成时用，过滤时总是通过
        // 你可以继续注册更多操作符
    }

    public static void registerOperator(String op, BiFunction<Object, Object, Boolean> func) {
        if (op == null) return;
        OPERATOR_MAP.put(op.toLowerCase(), func);
    }

    public static boolean evaluate(Object fieldValue, Map<String, Object> rule) {
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String op = entry.getKey();
            Object val = entry.getValue();
            if (op == null || !op.startsWith("$")) continue;
            BiFunction<Object, Object, Boolean> func = OPERATOR_MAP.get(op.toLowerCase());
            if (func == null) throw new UnsupportedOperationException("不支持的操作符: " + op);
            if (!func.apply(fieldValue, val)) return false;
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