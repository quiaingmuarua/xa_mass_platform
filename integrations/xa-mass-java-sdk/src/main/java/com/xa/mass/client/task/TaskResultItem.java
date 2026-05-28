package com.xa.mass.client.task;

import java.util.Map;

public record TaskResultItem(
        long seq,
        String messageId,
        String eventCode,
        String status,
        String finalReason,
        int retryCount,
        int maxRetryCount,
        String workerId,
        String batchId,
        String attemptId,
        String payloadRef,
        String errorCode,
        String errorMessage,
        Map<String, Object> output
) {
}
