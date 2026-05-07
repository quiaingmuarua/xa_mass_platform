package com.xa.mass.sdk;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SDK-owned compatibility projection view for one logical task message.
 */
public record SdkTaskMessageView(String messageId,
                                 String taskId,
                                 String status,
                                 String latestAttemptId,
                                 String latestAttemptWorkerId,
                                 String latestAttemptWorkerContextId,
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
