package com.xa.mass.engine;

import java.util.Map;

/**
 * Engine event payload for one logical task message reaching stable finality.
 */
public record TaskWorkLogicallyFinalEvent(
        String taskId,
        String messageId,
        TaskWorkProjectionState.MessageStatus status,
        TaskWorkProjectionState.MessageFinalReason finalReason,
        int retryCount,
        String errorCode,
        String errorMessage,
        String payloadRef,
        Map<String, Object> output
) {

    static TaskWorkLogicallyFinalEvent from(String taskId,
                                               String messageId,
                                               TaskWorkProjectionState.MessageStatus status,
                                               TaskWorkProjectionState.MessageFinalReason finalReason,
                                               int retryCount,
                                               String errorCode,
                                               String errorMessage,
                                               String payloadRef,
                                               Map<String, Object> output) {
        return new TaskWorkLogicallyFinalEvent(
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
