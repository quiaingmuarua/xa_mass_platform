package com.xa.mass.engine.work;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskWorkEnvelope(String taskId,
                               String messageId,
                               String eventCode,
                               Map<String, Object> payload,
                               String payloadRef,
                               int retryCount,
                               int maxRetryCount,
                               String shardKey,
                               Instant nextVisibleAt,
                               Instant createdAt) {

    public TaskWorkEnvelope {
        payload = copy(payload);
        retryCount = Math.max(0, retryCount);
        maxRetryCount = Math.max(0, maxRetryCount);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public TaskWorkEnvelope withRetry(int nextRetryCount, Instant nextVisibleAt) {
        return new TaskWorkEnvelope(taskId, messageId, eventCode, payload, payloadRef,
                nextRetryCount, maxRetryCount, shardKey, nextVisibleAt, createdAt);
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }
}
