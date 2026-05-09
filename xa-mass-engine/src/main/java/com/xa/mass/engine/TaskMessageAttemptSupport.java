package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

/**
 * Narrow helper for runtime attempt correlation and compatibility validation.
 */
public final class TaskMessageAttemptSupport {

    private TaskMessageAttemptSupport() {
    }

    public static String runtimeAttemptId(String messageId,
                                          int attemptNo,
                                          ActiveLeaseRecord activeLease) {
        String normalizedMessageId = messageId == null || messageId.isBlank() ? "unknown-message" : messageId;
        if (activeLease == null) {
            return "runtime-attempt-" + normalizedMessageId + "-" + attemptNo;
        }
        return runtimeAttemptId(
                messageId,
                attemptNo,
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId()
        );
    }

    public static String runtimeAttemptId(String messageId,
                                          int attemptNo,
                                          String workerId,
                                          String workerContextId,
                                          String batchId) {
        String normalizedMessageId = messageId == null || messageId.isBlank() ? "unknown-message" : messageId;
        return "runtime-attempt-"
                + normalizedMessageId
                + "-" + attemptNo
                + "-" + normalizeAttemptIdToken(workerId)
                + "-" + normalizeAttemptIdToken(workerContextId)
                + "-" + normalizeAttemptIdToken(batchId);
    }

    @CompatibilityProjectionOnly
    static boolean isTaskMessageFinalReasonCompatible(TaskMessageProjectionStatus status,
                                                      TaskMessageProjectionFinalReason finalReason) {
        if (status == null || !status.isFinal() || finalReason == null) {
            return false;
        }
        return switch (status) {
            case SUCCESS -> finalReason == TaskMessageProjectionFinalReason.BUSINESS_SUCCESS;
            case FAILED -> finalReason == TaskMessageProjectionFinalReason.BUSINESS_FAILED
                    || finalReason == TaskMessageProjectionFinalReason.MANUAL_CANCELLED
                    || finalReason == TaskMessageProjectionFinalReason.RETRY_EXHAUSTED;
            case EXPIRED -> finalReason == TaskMessageProjectionFinalReason.TIMEOUT
                    || finalReason == TaskMessageProjectionFinalReason.WORKER_LOST
                    || finalReason == TaskMessageProjectionFinalReason.MANUAL_CANCELLED
                    || finalReason == TaskMessageProjectionFinalReason.LEASE_EXPIRED;
            default -> false;
        };
    }

    private static String normalizeAttemptIdToken(String value) {
        if (value == null || value.isBlank()) {
            return "na";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
