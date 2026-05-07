package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

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
    static boolean isTaskMsgFinalReasonCompatible(TaskMsg taskMsg) {
        if (taskMsg == null || !taskMsg.isCompleted() || taskMsg.getFinalReason() == null) {
            return false;
        }
        return switch (taskMsg.getStatus()) {
            case SUCCESS -> taskMsg.getFinalReason() == TaskMsgFinalReason.BUSINESS_SUCCESS;
            case FAILED -> taskMsg.getFinalReason() == TaskMsgFinalReason.BUSINESS_FAILED
                    || taskMsg.getFinalReason() == TaskMsgFinalReason.MANUAL_CANCELLED
                    || taskMsg.getFinalReason() == TaskMsgFinalReason.RETRY_EXHAUSTED;
            case EXPIRED -> taskMsg.getFinalReason() == TaskMsgFinalReason.TIMEOUT
                    || taskMsg.getFinalReason() == TaskMsgFinalReason.WORKER_LOST
                    || taskMsg.getFinalReason() == TaskMsgFinalReason.MANUAL_CANCELLED
                    || taskMsg.getFinalReason() == TaskMsgFinalReason.LEASE_EXPIRED;
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
