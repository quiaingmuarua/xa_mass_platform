package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;

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
    static boolean isTaskMessageFinalReasonCompatible(CompatibilityMessageProjection taskMsg) {
        if (taskMsg == null || taskMsg.status() == null || !taskMsg.status().isFinal() || taskMsg.finalReason() == null) {
            return false;
        }
        return switch (taskMsg.status()) {
            case SUCCESS -> taskMsg.finalReason() == TaskMessageProjectionFinalReason.BUSINESS_SUCCESS;
            case FAILED -> taskMsg.finalReason() == TaskMessageProjectionFinalReason.BUSINESS_FAILED
                    || taskMsg.finalReason() == TaskMessageProjectionFinalReason.MANUAL_CANCELLED
                    || taskMsg.finalReason() == TaskMessageProjectionFinalReason.RETRY_EXHAUSTED;
            case EXPIRED -> taskMsg.finalReason() == TaskMessageProjectionFinalReason.TIMEOUT
                    || taskMsg.finalReason() == TaskMessageProjectionFinalReason.WORKER_LOST
                    || taskMsg.finalReason() == TaskMessageProjectionFinalReason.MANUAL_CANCELLED
                    || taskMsg.finalReason() == TaskMessageProjectionFinalReason.LEASE_EXPIRED;
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
