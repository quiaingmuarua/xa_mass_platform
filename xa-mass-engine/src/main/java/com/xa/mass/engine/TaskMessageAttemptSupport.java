package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Shared helper methods for task-message attempt lifecycle handling.
 */
public final class TaskMessageAttemptSupport {

    private TaskMessageAttemptSupport() {
    }

    public static TaskMsgAttempt buildDispatchedProjection(String attemptId,
                                                           String taskId,
                                                           String messageId,
                                                           int attemptNo,
                                                           String workerId,
                                                           String workerContextId,
                                                           String batchId,
                                                           Instant leaseExpireAt) {
        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId, taskId, messageId, attemptNo);
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        attempt.setBatchId(batchId);
        if (leaseExpireAt != null) {
            attempt.setLeaseExpireTime(LocalDateTime.ofInstant(leaseExpireAt, ZoneId.systemDefault()));
        }
        attempt.setStatus(TaskMsgAttemptStatus.DISPATCHED);
        return attempt;
    }

    public static TaskMsgAttempt buildDispatchedProjection(String taskId,
                                                           String messageId,
                                                           ActiveLeaseRecord activeLease,
                                                           String attemptId,
                                                           int attemptNo) {
        if (taskId == null || messageId == null || activeLease == null) {
            return null;
        }
        return buildDispatchedProjection(
                attemptId,
                taskId,
                messageId,
                attemptNo,
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId(),
                activeLease.leaseExpireAt()
        );
    }

    static boolean projectCallbackAccepted(TaskMsgAttempt attempt, long leaseSeconds) {
        if (attempt == null) {
            return false;
        }
        TaskMsgAttemptStatus currentStatus = attempt.getStatus();
        if (currentStatus == null) {
            return false;
        }
        if (currentStatus.isFinal()) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        if (attempt.getLeaseExpireTime() == null) {
            attempt.setLeaseExpireTime(now.plusSeconds(leaseSeconds));
        }
        if (attempt.getDispatchTime() == null) {
            attempt.setDispatchTime(now);
        }
        if (attempt.getAckTime() == null) {
            attempt.setAckTime(now);
        }
        if (attempt.getStartTime() == null) {
            attempt.setStartTime(now);
        }
        attempt.setStatus(TaskMsgAttemptStatus.RUNNING);
        return true;
    }

    static boolean projectExpired(TaskMsgAttempt attempt,
                                  TaskMsgAttemptFinalReason finalReason,
                                  String errorMessage) {
        if (attempt == null || attempt.getStatus() == null || attempt.getStatus().isFinal()) {
            return false;
        }
        attempt.setStatus(TaskMsgAttemptStatus.EXPIRED);
        attempt.setFinalReason(finalReason);
        attempt.setErrorMessage(errorMessage);
        return true;
    }

    static boolean projectSucceeded(TaskMsgAttempt attempt, java.util.Map<String, Object> output) {
        if (attempt == null || attempt.getStatus() == null || attempt.getStatus().isFinal()) {
            return false;
        }
        if (attempt.getStartTime() == null) {
            attempt.setStartTime(LocalDateTime.now());
        }
        attempt.setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        attempt.setFinalReason(TaskMsgAttemptFinalReason.SUCCESS);
        attempt.setOutput(output);
        return true;
    }

    static boolean projectRetryRevoked(TaskMsgAttempt attempt,
                                       String errorMessage,
                                       String errorCode) {
        if (attempt == null || attempt.getStatus() == null || attempt.getStatus().isFinal()) {
            return false;
        }
        attempt.setStatus(TaskMsgAttemptStatus.REVOKED);
        attempt.setFinalReason(TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY);
        attempt.setErrorMessage(errorMessage);
        attempt.setErrorCode(errorCode);
        attempt.setOutput(null);
        return true;
    }

    static boolean projectFailed(TaskMsgAttempt attempt,
                                 TaskMsgAttemptFinalReason finalReason,
                                 String errorMessage,
                                 String errorCode,
                                 java.util.Map<String, Object> output) {
        if (attempt == null || attempt.getStatus() == null || attempt.getStatus().isFinal()) {
            return false;
        }
        if (attempt.getStartTime() == null) {
            attempt.setStartTime(LocalDateTime.now());
        }
        attempt.setStatus(TaskMsgAttemptStatus.FAILED);
        attempt.setFinalReason(finalReason);
        attempt.setErrorMessage(errorMessage);
        attempt.setErrorCode(errorCode);
        attempt.setOutput(output);
        return true;
    }

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
}
