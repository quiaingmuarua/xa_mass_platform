package com.xa.mass.engine;

import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;

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
        TaskMessageAttemptProjectionStatus status,
        TaskMessageAttemptProjectionFinalReason finalReason
) {

    public static TaskMessageAttemptClosedEvent from(String taskId,
                                                     String messageId,
                                                     String attemptId,
                                                     int attemptNo,
                                                     String workerId,
                                                     String workerContextId,
                                                     String batchId,
                                                     TaskMessageAttemptProjectionStatus status,
                                                     TaskMessageAttemptProjectionFinalReason finalReason) {
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
