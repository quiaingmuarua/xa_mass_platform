package com.xa.mass.sdk.model;

/**
 * SDK-level immutable snapshot of one closed work attempt.
 */
public record TaskWorkAttemptClosedSnapshot(String taskId,
                                            String messageId,
                                            String attemptId,
                                            int attemptNo,
                                            String workerId,
                                            String batchId,
                                            String status,
                                            String finalReason) {
}
