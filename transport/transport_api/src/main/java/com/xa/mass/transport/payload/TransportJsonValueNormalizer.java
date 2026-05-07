package com.xa.mass.transport.payload;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport payload boundary normalizer for JSON-safe values.
 *
 * <p>Transport payloads must remain stable across in-memory delivery, JSON
 * codecs, and durable queue round-trips. This normalizer accepts only JSON-safe
 * value shapes and returns deeply immutable views.</p>
 */
public final class TransportJsonValueNormalizer {

    private TransportJsonValueNormalizer() {
    }

    public static Map<String, Object> normalizeObject(Map<String, Object> payload, String fieldName) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(payload.size());
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = requireObjectKey(entry.getKey(), fieldName);
            normalized.put(key, normalizeValue(entry.getValue(), fieldName + "." + key));
        }
        return Collections.unmodifiableMap(normalized);
    }

    public static Object normalizeValue(Object value, String fieldPath) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map, fieldPath);
        }
        if (value instanceof List<?> list) {
            return normalizeList(list, fieldPath);
        }
        if (value instanceof Iterable<?> iterable) {
            return normalizeIterable(iterable, fieldPath);
        }
        if (value.getClass().isArray()) {
            return normalizeArray(value, fieldPath);
        }
        throw new IllegalArgumentException(fieldPath + " contains unsupported non-JSON value type: "
                + value.getClass().getName());
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source, String fieldPath) {
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(source.size());
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = requireNestedObjectKey(entry.getKey(), fieldPath);
            normalized.put(key, normalizeValue(entry.getValue(), fieldPath + "." + key));
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static List<Object> normalizeList(List<?> source, String fieldPath) {
        if (source.isEmpty()) {
            return List.of();
        }
        ArrayList<Object> normalized = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            normalized.add(normalizeValue(source.get(index), fieldPath + "[" + index + "]"));
        }
        return Collections.unmodifiableList(normalized);
    }

    private static List<Object> normalizeIterable(Iterable<?> source, String fieldPath) {
        ArrayList<Object> normalized = new ArrayList<>();
        int index = 0;
        for (Object entry : source) {
            normalized.add(normalizeValue(entry, fieldPath + "[" + index + "]"));
            index++;
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(normalized);
    }

    private static List<Object> normalizeArray(Object array, String fieldPath) {
        int length = Array.getLength(array);
        if (length == 0) {
            return List.of();
        }
        ArrayList<Object> normalized = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            normalized.add(normalizeValue(Array.get(array, index), fieldPath + "[" + index + "]"));
        }
        return Collections.unmodifiableList(normalized);
    }

    private static String requireObjectKey(String key, String fieldName) {
        if (key == null) {
            throw new IllegalArgumentException(fieldName + " contains null object key");
        }
        return key;
    }

    private static String requireNestedObjectKey(Object key, String fieldPath) {
        if (!(key instanceof String text)) {
            throw new IllegalArgumentException(fieldPath + " contains non-string object key");
        }
        return text;
    }
}
