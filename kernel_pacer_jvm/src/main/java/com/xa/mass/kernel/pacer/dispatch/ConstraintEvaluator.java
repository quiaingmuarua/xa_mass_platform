package com.xa.mass.kernel.pacer.dispatch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConstraintEvaluator {

    private static final Set<String> OPERATORS = Set.of(
            "$eq", "$equal", "$ne", "$gt", "$gte", "$lt", "$lte",
            "$in", "$exists"
    );
    private static final Object MISSING = new Object();

    private ConstraintEvaluator() {
    }

    public static Map<String, Map<String, Object>> compileMatchRules(
            Map<String, Object> document
    ) {
        if (document == null) {
            throw new IllegalArgumentException(
                    "match rules must be a mapping"
            );
        }
        LinkedHashMap<String, Map<String, Object>> compiled =
                new LinkedHashMap<>();
        document.forEach((fieldName, rawOperators) -> {
            if (fieldName == null || fieldName.isEmpty()) {
                throw new IllegalArgumentException(
                        "constraint field name must be non-empty"
                );
            }
            int separator = fieldName.indexOf('.');
            if (separator >= 0 && (separator == 0
                    || separator == fieldName.length() - 1)) {
                throw new IllegalArgumentException(
                        "qualified match rules require a domain and field"
                );
            }
            if (!(rawOperators instanceof Map<?, ?> operators)
                    || operators.isEmpty()) {
                throw new IllegalArgumentException(
                        "constraint field requires a non-empty operator map"
                );
            }
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            operators.forEach((rawOperator, value) -> {
                if (!(rawOperator instanceof String operator)
                        || !OPERATORS.contains(operator)) {
                    throw new IllegalArgumentException(
                            "unsupported constraint operator"
                    );
                }
                if ("$in".equals(operator)
                        && !(value instanceof List<?>)) {
                    throw new IllegalArgumentException(
                            "$in requires a non-string sequence"
                    );
                }
                if ("$exists".equals(operator)
                        && !(value instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "$exists requires a boolean"
                    );
                }
                normalized.put(
                        operator,
                        value instanceof List<?> list
                                ? Collections.unmodifiableList(
                                        new ArrayList<>(list)
                                )
                                : value
                );
            });
            compiled.put(
                    fieldName,
                    Collections.unmodifiableMap(normalized)
            );
        });
        return Collections.unmodifiableMap(compiled);
    }

    public static boolean evaluateMatchRules(
            Map<String, Object> context,
            Map<String, Map<String, Object>> matchRules
    ) {
        for (Map.Entry<String, Map<String, Object>> rule
                : matchRules.entrySet()) {
            Object actual = resolve(context, rule.getKey());
            if (actual == MISSING) {
                if (rule.getValue().size() != 1
                        || !Boolean.FALSE.equals(
                        rule.getValue().get("$exists")
                )) {
                    return false;
                }
                continue;
            }
            for (Map.Entry<String, Object> operator
                    : rule.getValue().entrySet()) {
                if (!matches(actual, operator.getKey(), operator.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object resolve(
            Map<String, Object> context,
            String fieldName
    ) {
        int separator = fieldName.indexOf('.');
        if (separator < 0) {
            return context.containsKey(fieldName)
                    ? context.get(fieldName)
                    : MISSING;
        }
        Object domain = context.get(fieldName.substring(0, separator));
        String field = fieldName.substring(separator + 1);
        if (!(domain instanceof Map<?, ?> values)
                || !values.containsKey(field)) {
            return MISSING;
        }
        return values.get(field);
    }

    private static boolean matches(
            Object actual,
            String operator,
            Object expected
    ) {
        return switch (operator) {
            case "$eq", "$equal" -> equalValues(actual, expected);
            case "$ne" -> !equalValues(actual, expected);
            case "$in" -> ((List<?>) expected).stream()
                    .anyMatch(value -> equalValues(actual, value));
            case "$exists" -> Boolean.TRUE.equals(expected);
            case "$gt" -> compare(actual, expected, comparison ->
                    comparison > 0);
            case "$gte" -> compare(actual, expected, comparison ->
                    comparison >= 0);
            case "$lt" -> compare(actual, expected, comparison ->
                    comparison < 0);
            case "$lte" -> compare(actual, expected, comparison ->
                    comparison <= 0);
            default -> false;
        };
    }

    private static boolean equalValues(Object left, Object right) {
        if (left instanceof Number leftNumber
                && right instanceof Number rightNumber) {
            return decimal(leftNumber).compareTo(decimal(rightNumber)) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean compare(
            Object left,
            Object right,
            java.util.function.IntPredicate predicate
    ) {
        if (left instanceof Number leftNumber
                && right instanceof Number rightNumber) {
            return predicate.test(
                    decimal(leftNumber).compareTo(decimal(rightNumber))
            );
        }
        if (left == null || right == null
                || !left.getClass().isInstance(right)
                || !(left instanceof Comparable comparable)) {
            return false;
        }
        try {
            return predicate.test(comparable.compareTo(right));
        } catch (ClassCastException error) {
            return false;
        }
    }

    private static BigDecimal decimal(Number value) {
        return new BigDecimal(value.toString());
    }
}
