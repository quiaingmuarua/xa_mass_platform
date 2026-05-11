package com.xa.mass.engine;

/**
 * Engine event payload for a closed execution attempt.
 */
public record TaskWorkAttemptClosedEvent(
        String taskId,
        String messageId,
        String attemptId,
        int attemptNo,
        String workerId,
        String workerContextId,
        String batchId,
        TaskWorkProjectionState.AttemptStatus status,
        TaskWorkProjectionState.AttemptFinalReason finalReason
) {

    public static TaskWorkAttemptClosedEvent from(String taskId,
                                                     String messageId,
                                                     String attemptId,
                                                     int attemptNo,
                                                     String workerId,
                                                     String workerContextId,
                                                     String batchId,
                                                     TaskWorkProjectionState.AttemptStatus status,
                                                     TaskWorkProjectionState.AttemptFinalReason finalReason) {
        return new TaskWorkAttemptClosedEvent(
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
