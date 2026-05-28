package com.xa.mass.client.worker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerRequestSupport {
    private WorkerRequestSupport() {
    }

    static String encode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path value is required");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static Map<String, String> copyStringMap(Map<String, String> values) {
        return values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    static Map<String, Object> copyObjectMap(Map<String, Object> values) {
        return values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    static <T> List<T> copyList(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }

    static <T> ArrayList<T> mutableList() {
        return new ArrayList<>();
    }

    static <K, V> LinkedHashMap<K, V> mutableMap() {
        return new LinkedHashMap<>();
    }
}
