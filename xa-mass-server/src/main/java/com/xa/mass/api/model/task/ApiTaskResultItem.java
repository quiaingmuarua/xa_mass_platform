package com.xa.mass.api.model.task;

import java.util.Map;

public record ApiTaskResultItem(
        long seq,
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
        String createTime,
        String assignedTime,
        String startTime,
        String completeTime,
        String updateTime,
        String errorCode,
        String errorMessage,
        Map<String, Object> output
) {
}
