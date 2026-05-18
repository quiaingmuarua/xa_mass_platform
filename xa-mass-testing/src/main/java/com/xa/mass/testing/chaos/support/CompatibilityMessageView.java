package com.xa.mass.testing.chaos.support;

import java.time.LocalDateTime;
import java.util.Map;

public record CompatibilityMessageView(String messageId,
                                       String taskId,
                                       String status,
                                       String latestAttemptId,
                                       String latestAttemptWorkerId,
                                       String latestAttemptBatchId,
                                       int retryCount,
                                       int maxRetryCount,
                                       String errorMessage,
                                       String errorCode,
                                       String finalReason,
                                       String payloadRef,
                                       Map<String, Object> input,
                                       Map<String, Object> output,
                                       LocalDateTime assignedTime,
                                       LocalDateTime createTime,
                                       LocalDateTime updateTime,
                                       LocalDateTime startTime,
                                       LocalDateTime completeTime) {
}
