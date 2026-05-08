package com.xa.mass.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-native logical work ingress item.
 *
 * <p>This is the engine hot-path intake carrier. It exists so runtime enqueue
 * and dispatch ownership do not depend on {@code TaskMsg} projection shape.</p>
 */
record RuntimeTaskIngressItem(String taskId,
                              String messageId,
                              Map<String, Object> inlinePayload,
                              String payloadRef,
                              int retryCount,
                              int maxRetryCount) {

    private static final String PAYLOAD_REF_KEY = "payloadRef";

    RuntimeTaskIngressItem {
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
                input,
                payloadRef,
                0,
                maxRetryCount
        );
    }

    Map<String, Object> projectedInput() {
        return Map.of();
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

    private static Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }
}
