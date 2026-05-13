package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TaskResultFinalDraft(
        String taskId,
        String messageId,
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
        String stageId
) {

    public TaskResultFinalDraft {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(messageId, "messageId");
        requireNonBlank(status, "status");
        output = copyMap(output);
        updateTime = updateTime == null ? Instant.now() : updateTime;
        completeTime = completeTime == null ? updateTime : completeTime;
        createTime = createTime == null ? updateTime : createTime;
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
        return Map.copyOf(copy);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
