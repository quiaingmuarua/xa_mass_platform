package com.xa.mass.sdk;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SDK-owned compatibility attempt audit view.
 */
public record SdkTaskMessageAttemptView(String attemptId,
                                        String taskId,
                                        String messageId,
                                        int attemptNo,
                                        String workerId,
                                        String workerContextId,
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
