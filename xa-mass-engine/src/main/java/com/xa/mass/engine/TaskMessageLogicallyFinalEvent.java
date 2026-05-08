package com.xa.mass.engine;

import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.util.Map;

/**
 * Engine event payload for one logical task message reaching stable finality.
 */
public record TaskMessageLogicallyFinalEvent(
        String taskId,
        String messageId,
        TaskMessageProjectionStatus status,
        TaskMessageProjectionFinalReason finalReason,
        int retryCount,
        String errorCode,
        String errorMessage,
        String payloadRef,
        Map<String, Object> output
) {

    static TaskMessageLogicallyFinalEvent from(String taskId,
                                               String messageId,
                                               TaskMessageProjectionStatus status,
                                               TaskMessageProjectionFinalReason finalReason,
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
