package com.xa.mass.integration.workerloadedrecovery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LoadedRecoveryJson {

    private LoadedRecoveryJson() {
    }

    static Map<String, Object> object(Object value, String owner) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(owner + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(owner + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static List<Object> array(Object value, String owner) {
        if (!(value instanceof List<?> raw)) {
            throw invalid(owner + " must be an array");
        }
        return new ArrayList<>(raw);
    }

    static String string(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw invalid(field + " must be a non-blank string");
        }
        return text;
    }

    static String optionalString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw invalid(field + " must be a string or null");
        }
        return text;
    }

    static long integer(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Long number) {
            return number;
        }
        if (raw instanceof BigDecimal number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException error) {
                throw invalid(field + " must be an integer", error);
            }
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        throw invalid(field + " must be an integer");
    }

    static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Worker Loaded Capacity + Recovery Stability response is invalid: " + message
        );
    }

    static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException(
                "Worker Loaded Capacity + Recovery Stability response is invalid: " + message,
                cause
        );
    }
}
