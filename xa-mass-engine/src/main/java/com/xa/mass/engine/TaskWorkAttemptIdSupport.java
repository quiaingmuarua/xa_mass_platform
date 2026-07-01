package com.xa.mass.engine;

import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;

/**
 * Narrow helper for runtime attempt correlation.
 */
public final class TaskWorkAttemptIdSupport {

    private TaskWorkAttemptIdSupport() {
    }

    public static String runtimeAttemptId(String messageId,
                                          int attemptNo,
                                          ActiveLeaseRepairCandidate activeLease) {
        String normalizedMessageId = messageId == null || messageId.isBlank() ? "unknown-message" : messageId;
        if (activeLease == null) {
            return "runtime-attempt-" + normalizedMessageId + "-" + attemptNo;
        }
        return workerLevelRuntimeAttemptId(
                messageId,
                attemptNo,
                activeLease.workerId(),
                activeLease.batchId()
        );
    }

    public static String workerLevelRuntimeAttemptId(String messageId,
                                                     int attemptNo,
                                                     String workerId,
                                                     String batchId) {
        String normalizedMessageId = messageId == null || messageId.isBlank() ? "unknown-message" : messageId;
        return "runtime-attempt-"
                + normalizedMessageId
                + "-" + attemptNo
                + "-" + normalizeAttemptIdToken(workerId)
                + "-" + normalizeAttemptIdToken(batchId);
    }

    private static String normalizeAttemptIdToken(String value) {
        if (value == null || value.isBlank()) {
            return "na";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
