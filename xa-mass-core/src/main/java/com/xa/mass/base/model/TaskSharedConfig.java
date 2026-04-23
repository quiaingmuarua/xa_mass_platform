package com.xa.mass.base.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conventional task shared-config keys.
 *
 * <p>These keys are optional business/runtime hints carried inside
 * {@link Task#getSharedConfig()}; they are not first-class task aggregate truth.
 */
public final class TaskSharedConfig {

    public static final String ROUTING_CODE = "routingCode";
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
}
