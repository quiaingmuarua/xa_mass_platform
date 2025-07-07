package com.xa.mass.base.jsondsl.util;

import java.util.List;
import java.util.Map;

public class FieldRuleEvaluator {
    public static boolean evaluate(Object fieldValue, Map<String, Object> rule) {
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String op = entry.getKey();
            Object val = entry.getValue();
            switch (op) {
                case "$eq":
                    if (fieldValue == null || !fieldValue.toString().equals(val.toString())) return false;
                    break;
                case "$ne":
                    if (fieldValue != null && fieldValue.toString().equals(val.toString())) return false;
                    break;
                case "$gt":
                    if (!compareNumber(fieldValue, val, 1)) return false;
                    break;
                case "$gte":
                    if (!compareNumber(fieldValue, val, 0, 1)) return false;
                    break;
                case "$lt":
                    if (!compareNumber(fieldValue, val, -1)) return false;
                    break;
                case "$lte":
                    if (!compareNumber(fieldValue, val, 0, -1)) return false;
                    break;
                case "$in":
                    if (!(val instanceof List) || fieldValue == null || !((List<?>) val).contains(fieldValue)) return false;
                    break;
                case "$choice":
                    // 生成时用，过滤时可忽略
                    break;
                case "$expr":
                    // 预留表达式支持
                    throw new UnsupportedOperationException("$expr not implemented");
                default:
                    throw new UnsupportedOperationException("不支持的操作符: " + op);
            }
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