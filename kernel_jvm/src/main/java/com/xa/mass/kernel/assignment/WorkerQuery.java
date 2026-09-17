package com.xa.mass.kernel.assignment;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Bounded immutable JSON transport. Only the named Matching function interprets input. */
public record WorkerQuery(String executorName, Object input) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BYTES = 64 * 1024;

    public WorkerQuery {
        if (executorName == null || executorName.isBlank()) {
            throw new IllegalArgumentException("executorName must be non-blank");
        }
        if (input == null) throw new IllegalArgumentException("input is required");
        input = capture(input, 0, new Budget());
    }

    /** Decode the raw envelope before a typed binder can coerce its values. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WorkerQuery parse(Map<?, ?> value) {
        if (value == null || !value.keySet().equals(Set.of("executorName", "input"))
                || !(value.get("executorName") instanceof String name)) {
            throw new IllegalArgumentException("Worker query requires executorName and input only");
        }
        return new WorkerQuery(name, value.get("input"));
    }

    private static Object capture(Object value, int depth, Budget budget) {
        if (value instanceof Map<?, ?> map) {
            container(map.size(), depth);
            budget.add(2 + Math.max(0, map.size() - 1));
            var result = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                scalar(key, budget);
                budget.add(1);
                result.put(key, capture(entry.getValue(), depth + 1, budget));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            container(list.size(), depth);
            budget.add(2 + Math.max(0, list.size() - 1));
            var result = new ArrayList<Object>(list.size());
            for (Object child : list) result.add(capture(child, depth + 1, budget));
            return Collections.unmodifiableList(result);
        }
        return scalar(value, budget);
    }

    private static void container(int size, int depth) {
        if (size > 100 || depth >= 8) {
            throw new IllegalArgumentException("JSON containers require at most 100 members and depth 8");
        }
    }

    private static Object scalar(Object value, Budget budget) {
        if (!(value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Float || value instanceof Double)) {
            throw new IllegalArgumentException("input must contain only JSON values");
        }
        if (value instanceof Double number && !Double.isFinite(number)
                || value instanceof Float floating && !Float.isFinite(floating)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof String text && text.length() > MAX_BYTES) {
            throw new IllegalArgumentException("input exceeds 64 KiB");
        }
        budget.add(JSON.writeValueAsBytes(value).length);
        return value;
    }

    private static final class Budget {
        private int bytes;
        void add(int size) {
            if (size > MAX_BYTES - bytes) throw new IllegalArgumentException("input exceeds 64 KiB");
            bytes += size;
        }
    }
}
