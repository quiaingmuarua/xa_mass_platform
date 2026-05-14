package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TaskResultRuntimeRow(
        String taskId,
        String messageId,
        long seq,
        String eventCode,
        String status,
        String finalReason,
        int retryCount,
        int maxRetryCount,
        String workerId,
        String workerContextId,
        String batchId,
        String attemptId,
        String payloadRef,
        Instant createTime,
        Instant assignedTime,
        Instant startTime,
        Instant completeTime,
        Instant updateTime,
        String errorCode,
        String errorMessage,
        Map<String, Object> output,
        boolean attemptClosedPublished,
        boolean logicalFinalPublished,
        boolean progressApplied
) {

    public TaskResultRuntimeRow {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(messageId, "messageId");
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be greater than 0");
        }
        requireNonBlank(status, "status");
        output = copyMap(output);
    }

    public TaskResultRuntimeRow withLogicalFinalPublished() {
        return new TaskResultRuntimeRow(taskId, messageId, seq, eventCode, status, finalReason,
                retryCount, maxRetryCount, workerId, workerContextId, batchId, attemptId, payloadRef,
                createTime, assignedTime, startTime, completeTime, updateTime, errorCode, errorMessage,
                output, attemptClosedPublished, true, progressApplied);
    }

    public TaskResultRuntimeRow withProgressApplied() {
        return new TaskResultRuntimeRow(taskId, messageId, seq, eventCode, status, finalReason,
                retryCount, maxRetryCount, workerId, workerContextId, batchId, attemptId, payloadRef,
                createTime, assignedTime, startTime, completeTime, updateTime, errorCode, errorMessage,
                output, attemptClosedPublished, logicalFinalPublished, true);
    }

    public TaskResultRuntimeRow withAttemptClosedPublished() {
        return new TaskResultRuntimeRow(taskId, messageId, seq, eventCode, status, finalReason,
                retryCount, maxRetryCount, workerId, workerContextId, batchId, attemptId, payloadRef,
                createTime, assignedTime, startTime, completeTime, updateTime, errorCode, errorMessage,
                output, true, logicalFinalPublished, progressApplied);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "output key"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
