package com.xa.mass.engine;

import java.util.Map;

/**
 * Engine event payload for one logical task message reaching stable finality.
 */
public record TaskMessageLogicallyFinalEvent(
        String taskId,
        String messageId,
        TaskMessageCompatibilityState.MessageStatus status,
        TaskMessageCompatibilityState.MessageFinalReason finalReason,
        int retryCount,
        String errorCode,
        String errorMessage,
        String payloadRef,
        Map<String, Object> output
) {

    static TaskMessageLogicallyFinalEvent from(String taskId,
                                               String messageId,
                                               TaskMessageCompatibilityState.MessageStatus status,
                                               TaskMessageCompatibilityState.MessageFinalReason finalReason,
                                               int retryCount,
                                               String errorCode,
                                               String errorMessage,
                                               String payloadRef,
                                               Map<String, Object> output) {
        return new TaskMessageLogicallyFinalEvent(
                taskId,
                messageId,
                status,
                finalReason,
                retryCount,
                errorCode,
                errorMessage,
                payloadRef,
                output == null ? null : new java.util.LinkedHashMap<>(output)
        );
    }
}
