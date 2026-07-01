package com.xa.mass.task.runtime;

import java.util.List;
import java.util.Map;

final class TaskRuntimeContractChecks {

    private TaskRuntimeContractChecks() {
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static <T> List<T> copyNonEmpty(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
        return List.copyOf(values);
    }

    static <T> List<T> copyList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    static Map<String, Object> copyPayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : Map.copyOf(payload);
    }
}
