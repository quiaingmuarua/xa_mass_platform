package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.storage.api.TaskDetailStore;

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
    static boolean isTaskMessageFinalReasonCompatible(TaskDetailStore.TaskMessageProjection taskMsg) {
        if (taskMsg == null || taskMsg.status() == null || !taskMsg.status().isFinal() || taskMsg.finalReason() == null) {
            return false;
        }
        return switch (taskMsg.status()) {
            case SUCCESS -> taskMsg.finalReason() == TaskMsgFinalReason.BUSINESS_SUCCESS;
            case FAILED -> taskMsg.finalReason() == TaskMsgFinalReason.BUSINESS_FAILED
                    || taskMsg.finalReason() == TaskMsgFinalReason.MANUAL_CANCELLED
                    || taskMsg.finalReason() == TaskMsgFinalReason.RETRY_EXHAUSTED;
            case EXPIRED -> taskMsg.finalReason() == TaskMsgFinalReason.TIMEOUT
                    || taskMsg.finalReason() == TaskMsgFinalReason.WORKER_LOST
                    || taskMsg.finalReason() == TaskMsgFinalReason.MANUAL_CANCELLED
                    || taskMsg.finalReason() == TaskMsgFinalReason.LEASE_EXPIRED;
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
