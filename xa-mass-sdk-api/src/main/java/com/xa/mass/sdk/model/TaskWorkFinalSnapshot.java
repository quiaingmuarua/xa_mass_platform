package com.xa.mass.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK-owned snapshot for one work item reaching stable finality.
 */
public record TaskWorkFinalSnapshot(
        String taskId,
        String messageId,
        String status,
        String finalReason,
        int retryCount,
        String errorCode,
        String errorMessage,
        String payloadRef,
        Map<String, Object> output
) {

    public TaskWorkFinalSnapshot {
        output = output == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(output));
    }
}
