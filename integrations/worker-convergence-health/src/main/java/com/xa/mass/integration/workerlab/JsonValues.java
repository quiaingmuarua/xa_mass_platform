package com.xa.mass.integration.workerlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonValues {

    private JsonValues() {
    }

    static String requiredString(Map<String, Object> value, String field) {
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

    static long requiredLong(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw invalid(field + " must be a number");
        }
        return number.longValue();
    }

    static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(name + " must be an object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(name + " keys must be strings");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    static List<Object> array(Object value, String name) {
        if (!(value instanceof List<?> raw)) {
            throw invalid(name + " must be an array");
        }
        return new ArrayList<>(raw);
    }

    static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Worker Lab convergence response is invalid: " + message
        );
    }
}
