package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;

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

    public static TaskMessageAttemptClosedEvent from(String taskId,
                                                     String messageId,
                                                     String attemptId,
                                                     int attemptNo,
                                                     String workerId,
                                                     String workerContextId,
                                                     String batchId,
                                                     TaskMsgAttemptStatus status,
                                                     TaskMsgAttemptFinalReason finalReason) {
        return new TaskMessageAttemptClosedEvent(
                taskId,
                messageId,
                attemptId,
                attemptNo,
                workerId,
                workerContextId,
                batchId,
                status,
                finalReason
        );
    }

}
