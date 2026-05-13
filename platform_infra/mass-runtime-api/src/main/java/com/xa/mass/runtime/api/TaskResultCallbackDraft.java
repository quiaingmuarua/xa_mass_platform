package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TaskResultCallbackDraft(
        String stageId,
        String taskId,
        String messageId,
        boolean success,
        String detail,
        String errorCode,
        Map<String, Object> output,
        Instant receivedAt,
        String attemptId,
        String leaseToken,
        String traceId,
        String adapterId,
        String routeKey,
        String identityDigest,
        String workerId,
        String workerContextId,
        String batchId,
        String payloadRef,
        String eventCode,
        int retryCount,
        int maxRetryCount,
        Instant leasedAt,
        Instant createTime
) {

    public TaskResultCallbackDraft {
        requireNonBlank(stageId, "stageId");
        requireNonBlank(taskId, "taskId");
        requireNonBlank(messageId, "messageId");
        requireNonBlank(identityDigest, "identityDigest");
        output = copyMap(output);
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }

    public static String stageId(String taskId, String messageId, String identityDigest) {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(messageId, "messageId");
        requireNonBlank(identityDigest, "identityDigest");
        return taskId + ":" + messageId + ":" + identityDigest;
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
