package com.xa.mass.kernel.pacer.dispatch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ConstraintEvaluator {

    List<Condition> normalize(Map<String, Object> allocationRule) {
        if (allocationRule == null) {
            throw new IllegalArgumentException("match rules must be a mapping");
        }
        List<Condition> conditions = new ArrayList<>();
        allocationRule.forEach((propertyName, rawOperators) -> {
            validatePropertyName(propertyName);
            if (!(rawOperators instanceof Map<?, ?> operators)
                    || operators.isEmpty()) {
                throw new IllegalArgumentException(
                        "constraint property requires operators"
                );
            }
            operators.forEach((operator, value) -> conditions.add(
                    normalizeCondition(propertyName, operator, value)
            ));
        });
        return List.copyOf(conditions);
    }

    boolean matches(
            List<Condition> conditions,
            String workerId,
            Map<String, Object> workerProperties,
            Map<String, Object> platformProperties
    ) {
        Objects.requireNonNull(conditions, "conditions");
        for (Condition condition : conditions) {
            Property actual = resolve(
                    condition.propertyName(),
                    workerId,
                    workerProperties,
                    platformProperties
            );
            if (!matches(actual, condition)) {
                return false;
            }
        }
        return true;
    }

    private static Condition normalizeCondition(
            String propertyName,
            Object rawOperator,
            Object rawValue
    ) {
        if (!(rawOperator instanceof String operator)) {
            throw new IllegalArgumentException(
                    "unsupported constraint operator"
            );
        }
        return switch (operator) {
            case "$eq", "$equal" -> one(propertyName, Operator.EQ, rawValue);
            case "$ne" -> one(propertyName, Operator.NE, rawValue);
            case "$gt" -> one(propertyName, Operator.GT, rawValue);
            case "$gte" -> one(propertyName, Operator.GTE, rawValue);
            case "$lt" -> one(propertyName, Operator.LT, rawValue);
            case "$lte" -> one(propertyName, Operator.LTE, rawValue);
            case "$in" -> {
                if (!(rawValue instanceof List<?> values)) {
                    throw new IllegalArgumentException(
                            "$in requires a sequence"
                    );
                }
                yield new Condition(
                        propertyName,
                        Operator.IN,
                        values.stream().map(
                                ConstraintEvaluator::snapshot
                        ).toList()
                );
            }
            case "$exists" -> {
                if (!(rawValue instanceof Boolean exists)) {
                    throw new IllegalArgumentException(
                            "$exists requires a boolean"
                    );
                }
                yield new Condition(
                        propertyName,
                        exists ? Operator.EXISTS : Operator.NOT_EXISTS,
                        List.of()
                );
            }
            case "$range" -> {
                if (!(rawValue instanceof List<?> values)
                        || values.size() != 2) {
                    throw new IllegalArgumentException(
                            "$range requires two bounds"
                    );
                }
                Object lower = snapshot(values.get(0));
                Object upper = snapshot(values.get(1));
                Integer ordered = compareValues(lower, upper);
                if (lower == null || upper == null
                        || ordered == null || ordered > 0) {
                    throw new IllegalArgumentException(
                            "$range bounds must be comparable and ordered"
                    );
                }
                yield new Condition(
                        propertyName,
                        Operator.RANGE,
                        List.of(lower, upper)
                );
            }
            default -> throw new IllegalArgumentException(
                    "unsupported constraint operator"
            );
        };
    }

    private static Condition one(
            String propertyName,
            Operator operator,
            Object value
    ) {
        return new Condition(
                propertyName,
                operator,
                Collections.singletonList(snapshot(value))
        );
    }

    private static Object snapshot(Object value) {
        return value instanceof List<?> list
                ? Collections.unmodifiableList(new ArrayList<>(list))
                : value;
    }

    private static void validatePropertyName(String propertyName) {
        if (propertyName == null || propertyName.isEmpty()
                || !("workerId".equals(propertyName)
                || propertyName.startsWith("worker.")
                && propertyName.length() > "worker.".length()
                || propertyName.startsWith("platform.")
                && propertyName.length() > "platform.".length())) {
            throw new IllegalArgumentException(
                    "unsupported Worker allocation property"
            );
        }
    }

    private static Property resolve(
            String propertyName,
            String workerId,
            Map<String, Object> workerProperties,
            Map<String, Object> platformProperties
    ) {
        if ("workerId".equals(propertyName)) {
            return new Property(true, workerId);
        }
        boolean workerProperty = propertyName.startsWith("worker.");
        Map<String, Object> properties = workerProperty
                ? workerProperties
                : platformProperties;
        String name = propertyName.substring(
                workerProperty ? "worker.".length() : "platform.".length()
        );
        return properties.containsKey(name)
                ? new Property(true, properties.get(name))
                : new Property(false, null);
    }

    private static boolean matches(Property actual, Condition condition) {
        Operator operator = condition.operator();
        if (!actual.present()) {
            return operator == Operator.NOT_EXISTS;
        }
        Object first = condition.params().isEmpty()
                ? null
                : condition.params().getFirst();
        return switch (operator) {
            case EQ -> equalValues(actual.value(), first);
            case NE -> !equalValues(actual.value(), first);
            case IN -> condition.params().stream().anyMatch(
                    value -> equalValues(actual.value(), value)
            );
            case EXISTS -> true;
            case NOT_EXISTS -> false;
            case GT -> compare(actual.value(), first, value -> value > 0);
            case GTE -> compare(actual.value(), first, value -> value >= 0);
            case LT -> compare(actual.value(), first, value -> value < 0);
            case LTE -> compare(actual.value(), first, value -> value <= 0);
            case RANGE -> compare(
                    actual.value(),
                    first,
                    value -> value >= 0
            ) && compare(
                    actual.value(),
                    condition.params().get(1),
                    value -> value <= 0
            );
        };
    }

    private static boolean equalValues(Object left, Object right) {
        if (left instanceof Number leftNumber
                && right instanceof Number rightNumber) {
            return decimal(leftNumber).compareTo(decimal(rightNumber)) == 0;
        }
        return Objects.equals(left, right);
    }

    private static boolean compare(
            Object left,
            Object right,
            java.util.function.IntPredicate predicate
    ) {
        Integer comparison = compareValues(left, right);
        return comparison != null && predicate.test(comparison);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Integer compareValues(Object left, Object right) {
        if (left instanceof Number leftNumber
                && right instanceof Number rightNumber) {
            return decimal(leftNumber).compareTo(decimal(rightNumber));
        }
        if (left == null || right == null
                || !left.getClass().isInstance(right)
                || !(left instanceof Comparable comparable)) {
            return null;
        }
        try {
            return comparable.compareTo(right);
        } catch (ClassCastException error) {
            return null;
        }
    }

    private static BigDecimal decimal(Number value) {
        return new BigDecimal(value.toString());
    }

    record Condition(
            String propertyName,
            Operator operator,
            List<Object> params
    ) {
        Condition {
            params = Collections.unmodifiableList(new ArrayList<>(params));
        }
    }

    enum Operator {
        EQ, NE, GT, GTE, LT, LTE, IN, EXISTS, NOT_EXISTS, RANGE
    }

    private record Property(boolean present, Object value) {
    }
}
