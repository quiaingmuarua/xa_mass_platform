package com.xa.mass.integration.androidworkerproof;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonValues {

    private JsonValues() {
    }

    static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(name + " must be an object");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(name + " keys must be strings");
            }
            copied.put(key, entry.getValue());
        }
        return copied;
    }

    static List<Object> array(Object value, String name) {
        if (!(value instanceof List<?> raw)) {
            throw invalid(name + " must be an array");
        }
        return new ArrayList<>(raw);
    }

    static String requiredString(Map<String, Object> value, String name) {
        Object raw = value.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw invalid(name + " must be non-blank");
        }
        return text;
    }

    static String optionalString(Object value, String name) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid(name + " must be a string or null");
        }
        return text;
    }

    static long requiredLong(Map<String, Object> value, String name) {
        Object raw = value.get(name);
        if (!(raw instanceof Number number)) {
            throw invalid(name + " must be an integer");
        }
        return number.longValue();
    }

    static ProofFailure invalid(String message) {
        return new ProofFailure("json.contract", message);
    }
}
