package com.xa.mass.kernel.task.redis;

import java.util.Map;
import java.util.Set;

final class TaskConstraintRuleValidator {

    private static final Set<String> SUPPORTED_OPERATORS = Set.of(
            "$eq",
            "$equal",
            "$ne",
            "$gt",
            "$gte",
            "$lt",
            "$lte",
            "$in",
            "$exists"
    );

    private TaskConstraintRuleValidator() {
    }

    static void validate(Map<String, Object> rules) {
        for (Map.Entry<String, Object> entry : rules.entrySet()) {
            String fieldName = entry.getKey();
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
            if (!(entry.getValue() instanceof Map<?, ?> operators)
                    || operators.isEmpty()) {
                throw new IllegalArgumentException(
                        "constraint field requires a non-empty operator map"
                );
            }
            validateOperators(operators);
        }
    }

    private static void validateOperators(Map<?, ?> operators) {
        for (Map.Entry<?, ?> entry : operators.entrySet()) {
            if (!(entry.getKey() instanceof String operator)
                    || !SUPPORTED_OPERATORS.contains(operator)) {
                throw new IllegalArgumentException(
                        "unsupported constraint operator"
                );
            }
            if ("$in".equals(operator)
                    && !(entry.getValue() instanceof java.util.List<?>)) {
                throw new IllegalArgumentException(
                        "$in requires a non-string sequence"
                );
            }
            if ("$exists".equals(operator)
                    && !(entry.getValue() instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "$exists requires a boolean"
                );
            }
        }
    }
}
