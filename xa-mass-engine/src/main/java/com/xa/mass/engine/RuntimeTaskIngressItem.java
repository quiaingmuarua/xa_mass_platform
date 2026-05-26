package com.xa.mass.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-native logical work ingress item.
 *
 * <p>This is the engine hot-path intake carrier. It exists so runtime enqueue
 * and dispatch ownership do not depend on compatibility projection shape.</p>
 */
record RuntimeTaskIngressItem(String taskId,
                              String messageId,
                              String eventCode,
                              Map<String, Object> inlinePayload,
                              String payloadRef,
                              int retryCount,
                              int maxRetryCount) {

    private static final String EVENT_CODE_KEY = "eventCode";
    private static final String PAYLOAD_REF_KEY = "payloadRef";

    RuntimeTaskIngressItem {
        eventCode = normalizeString(eventCode);
        inlinePayload = copy(inlinePayload);
        retryCount = Math.max(0, retryCount);
        maxRetryCount = Math.max(0, maxRetryCount);
    }

    static RuntimeTaskIngressItem fromInput(String taskId,
                                            String messageId,
                                            Map<String, Object> input,
                                            int maxRetryCount) {
        String payloadRef = extractPayloadRef(input);
        return new RuntimeTaskIngressItem(
                taskId,
                messageId,
                extractEventCode(input),
                stripControlFields(input),
                payloadRef,
                0,
                maxRetryCount
        );
    }

    Map<String, Object> projectedInput() {
        LinkedHashMap<String, Object> projected = new LinkedHashMap<>();
        if (eventCode != null) {
            projected.put(EVENT_CODE_KEY, eventCode);
        }
        if (inlinePayload != null && !inlinePayload.isEmpty()) {
            projected.putAll(inlinePayload);
        }
        if (payloadRef != null) {
            projected.put(PAYLOAD_REF_KEY, payloadRef);
        }
        return projected.isEmpty() ? Map.of() : Map.copyOf(projected);
    }

    private static String extractPayloadRef(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Object value = input.get(PAYLOAD_REF_KEY);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static String extractEventCode(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Object value = input.get(EVENT_CODE_KEY);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static Map<String, Object> stripControlFields(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(values);
        copy.remove(EVENT_CODE_KEY);
        copy.remove(PAYLOAD_REF_KEY);
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    private static String normalizeString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
