package com.xa.mass.base.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Conventional task shared-config keys.
 *
 * <p>These keys are optional business/runtime hints carried inside
 * {@link Task#getSharedConfig()}; they are not first-class task aggregate truth.
 */
public final class TaskSharedConfig {

    public static final String ROUTING_CODE = "routingCode";
    public static final String ROUTE_ATTRIBUTES = "routeAttributes";
    public static final String WORKER_GROUP_ID = "workerGroupId";
    public static final String WORKER_GROUP_IDS = "workerGroupIds";
    public static final String ADAPTER_NODE_ID = "adapterNodeId";
    public static final String TARGET_WORKER_ID = "targetWorkerId";
    public static final String TARGET_WORKER_ATTRIBUTES = "targetWorkerAttributes";
    public static final String SDK_METADATA = "_sdk";
    public static final String SDK_EVENT_CODE = "eventCode";

    private TaskSharedConfig() {
    }

    public static String routingCode(Task task) {
        if (task == null) {
            return null;
        }
        return stringValue(task.getSharedConfig(), ROUTING_CODE);
    }

    public static String sdkEventCode(Task task) {
        if (task == null) {
            return null;
        }
        return sdkStringValue(task.getSharedConfig(), SDK_EVENT_CODE);
    }

    public static String targetWorkerId(Task task) {
        if (task == null) {
            return null;
        }
        return stringValue(task.getSharedConfig(), TARGET_WORKER_ID);
    }

    public static String adapterNodeId(Task task) {
        if (task == null) {
            return null;
        }
        return stringValue(task.getSharedConfig(), ADAPTER_NODE_ID);
    }

    public static List<String> workerGroupSelector(Task task) {
        if (task == null) {
            return List.of();
        }
        return workerGroupSelector(task.getSharedConfig());
    }

    public static List<String> workerGroupSelector(Map<String, Object> sharedConfig) {
        if (sharedConfig == null || sharedConfig.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        addNormalized(normalized, sharedConfig.get(WORKER_GROUP_ID));
        addNormalized(normalized, sharedConfig.get(WORKER_GROUP_IDS));
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    public static Map<String, String> targetWorkerAttributes(Task task) {
        if (task == null) {
            return Map.of();
        }
        return stringMapValue(task.getSharedConfig(), TARGET_WORKER_ATTRIBUTES);
    }

    public static Map<String, String> routeAttributes(Task task) {
        if (task == null) {
            return Map.of();
        }
        return stringMapValue(task.getSharedConfig(), ROUTE_ATTRIBUTES);
    }

    public static String stringValue(Map<String, Object> sharedConfig, String key) {
        if (sharedConfig == null || key == null) {
            return null;
        }
        Object value = sharedConfig.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static Map<String, Object> withRoutingCode(Map<String, Object> sharedConfig, String routingCode) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (sharedConfig != null) {
            copy.putAll(sharedConfig);
        }
        if (routingCode != null && !routingCode.isBlank()) {
            copy.putIfAbsent(ROUTING_CODE, routingCode.trim());
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static String sdkStringValue(Map<String, Object> sharedConfig, String key) {
        if (sharedConfig == null || key == null) {
            return null;
        }
        Object sdk = sharedConfig.get(SDK_METADATA);
        if (!(sdk instanceof Map<?, ?> sdkMetadata)) {
            return null;
        }
        Object value = ((Map<String, Object>) sdkMetadata).get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static void addNormalized(LinkedHashSet<String> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                addNormalized(target, item);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addNormalized(target, java.lang.reflect.Array.get(value, i));
            }
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            target.add(text);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMapValue(Map<String, Object> sharedConfig, String key) {
        if (sharedConfig == null || key == null) {
            return Map.of();
        }
        Object value = sharedConfig.get(key);
        if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) rawMap).entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String normalizedKey = String.valueOf(entry.getKey()).trim();
            String normalizedValue = String.valueOf(entry.getValue()).trim();
            if (!normalizedKey.isEmpty() && !normalizedValue.isEmpty()) {
                normalized.put(normalizedKey, normalizedValue);
            }
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }
}
