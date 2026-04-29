package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskWorkResult(String taskId,
                             String messageId,
                             String leaseToken,
                             boolean success,
                             boolean expired,
                             String errorCode,
                             String detail,
                             Map<String, Object> output,
                             String outputRef,
                             Instant retryVisibleAt,
                             Instant completedAt,
                             boolean retryable) {

    public TaskWorkResult {
        output = output == null || output.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        completedAt = completedAt == null ? Instant.now() : completedAt;
    }

    public static TaskWorkResult success(String taskId,
                                         String messageId,
                                         String leaseToken,
                                         String detail,
                                         Map<String, Object> output) {
        return new TaskWorkResult(taskId, messageId, leaseToken, true, false, null, detail, output, null, null, Instant.now(), false);
    }

    public static TaskWorkResult failure(String taskId,
                                         String messageId,
                                         String leaseToken,
                                         String errorCode,
                                         String detail,
                                         Map<String, Object> output,
                                         boolean retryable) {
        return new TaskWorkResult(taskId, messageId, leaseToken, false, false, errorCode, detail, output, null, null, Instant.now(), retryable);
    }

    public static TaskWorkResult expired(String taskId,
                                         String messageId,
                                         String leaseToken,
                                         String detail,
                                         boolean retryable) {
        return new TaskWorkResult(taskId, messageId, leaseToken, false, true, "LEASE_EXPIRED", detail, Map.of(),
                null, null, Instant.now(), retryable);
    }

    public TaskWorkResult withRetryVisibleAt(Instant retryVisibleAt) {
        return new TaskWorkResult(taskId, messageId, leaseToken, success, expired, errorCode, detail,
                output, outputRef, retryVisibleAt, completedAt, retryable);
    }
}

