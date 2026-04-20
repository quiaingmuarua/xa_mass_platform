package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.time.LocalDateTime;

/**
 * Shared helper methods for task-message attempt lifecycle handling.
 */
final class TaskMessageAttemptSupport {

    private TaskMessageAttemptSupport() {
    }

    static TaskMsgAttempt ensureLegacyAttempt(TaskManager manager, TaskMsg taskMsg) {
        if (taskMsg == null || taskMsg.getMsgId() == null || taskMsg.getTaskId() == null) {
            return null;
        }
        TaskMsgAttempt existing = manager.getLatestActiveTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMsgId());
        if (existing != null) {
            return existing;
        }
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                java.util.UUID.randomUUID().toString(),
                taskMsg.getTaskId(),
                taskMsg.getMsgId(),
                manager.getTaskMessageAttempts(taskMsg.getTaskId(), taskMsg.getMsgId()).size() + 1
        );
        attempt.setWorkerId(taskMsg.getWorkerId());
        attempt.setWorkerContextId(taskMsg.getWorkerContextId());
        attempt.setBatchId(taskMsg.getBatchId());
        attempt.markLeased(LocalDateTime.now().plusMinutes(5));
        attempt.markDispatched();
        if (taskMsg.getStatus() == TaskMsgStatus.RUNNING) {
            attempt.markRunning();
        }
        manager.addTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMsgId(), attempt);
        return attempt;
    }

    static boolean advanceAttemptForCallback(TaskMsgAttempt attempt) {
        if (attempt == null || attempt.getStatus() == null) {
            return false;
        }
        if (attempt.getStatus().isFinal()) {
            return true;
        }
        return switch (attempt.getStatus()) {
            case CREATED -> attempt.markLeased(LocalDateTime.now().plusMinutes(5))
                    && attempt.markDispatched()
                    && attempt.markRunning();
            case LEASED -> attempt.markDispatched() && attempt.markRunning();
            case DISPATCHED, ACKED -> attempt.markRunning();
            case RUNNING -> true;
            default -> false;
        };
    }

    static boolean expireAttempt(TaskMsgAttempt attempt,
                                 TaskMsgAttemptFinalReason finalReason,
                                 String errorMessage) {
        if (attempt == null || attempt.getStatus() == null || attempt.getStatus().isFinal()) {
            return false;
        }
        return switch (attempt.getStatus()) {
            case CREATED, LEASED, DISPATCHED, ACKED, RUNNING -> attempt.markExpired(finalReason, errorMessage);
            default -> false;
        };
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
