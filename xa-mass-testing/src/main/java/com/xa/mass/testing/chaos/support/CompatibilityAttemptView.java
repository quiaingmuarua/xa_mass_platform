package com.xa.mass.testing.chaos.support;

import java.time.LocalDateTime;
import java.util.Map;

public record CompatibilityAttemptView(String attemptId,
                                       String taskId,
                                       String messageId,
                                       int attemptNo,
                                       String workerId,
                                       String batchId,
                                       String status,
                                       LocalDateTime leaseExpireTime,
                                       LocalDateTime dispatchTime,
                                       LocalDateTime ackTime,
                                       LocalDateTime startTime,
                                       LocalDateTime finishTime,
                                       String finalReason,
                                       String errorMessage,
                                       String errorCode,
                                       Map<String, Object> output,
                                       LocalDateTime createTime,
                                       LocalDateTime updateTime) {
}
