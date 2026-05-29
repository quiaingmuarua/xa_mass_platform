package com.xa.mass.sdk.model;

import java.time.Instant;
import java.util.Collections;
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
        int maxRetryCount,
        String eventCode,
        String workerId,
        String batchId,
        String attemptId,
        String errorCode,
        String errorMessage,
        String payloadRef,
        Instant createTime,
        Instant assignedTime,
        Instant startTime,
        Instant completeTime,
        Instant updateTime,
        Map<String, Object> output
) {

    public TaskWorkFinalSnapshot {
        retryCount = Math.max(0, retryCount);
        maxRetryCount = Math.max(0, maxRetryCount);
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public TaskWorkFinalSnapshot(String taskId,
                                 String messageId,
                                 String status,
                                 String finalReason,
                                 int retryCount,
                                 String errorCode,
                                 String errorMessage,
                                 String payloadRef,
                                 Map<String, Object> output) {
        this(taskId,
                messageId,
                status,
                finalReason,
                retryCount,
                0,
                null,
                null,
                null,
                null,
                errorCode,
                errorMessage,
                payloadRef,
                null,
                null,
                null,
                null,
                null,
                output);
    }
}
