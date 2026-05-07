package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.model.TaskMsgAttempt;

/**
 * Engine event payload for a closed execution attempt.
 */
public record TaskMessageAttemptClosedEvent(
        String taskId,
        String messageId,
        String attemptId,
        int attemptNo,
        String workerId,
        String workerContextId,
        String batchId,
        TaskMsgAttemptStatus status,
        TaskMsgAttemptFinalReason finalReason
) {

    public static TaskMessageAttemptClosedEvent from(String taskId, String messageId, TaskMsgAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        return new TaskMessageAttemptClosedEvent(
                taskId,
                messageId,
                attempt.getAttemptId(),
                attempt.getAttemptNo(),
                attempt.getWorkerId(),
                attempt.getWorkerContextId(),
                attempt.getBatchId(),
                attempt.getStatus(),
                attempt.getFinalReason()
        );
    }
}
