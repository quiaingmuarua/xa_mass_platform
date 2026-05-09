package com.xa.mass.engine;

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
        TaskMessageCompatibilityState.AttemptStatus status,
        TaskMessageCompatibilityState.AttemptFinalReason finalReason
) {

    public static TaskMessageAttemptClosedEvent from(String taskId,
                                                     String messageId,
                                                     String attemptId,
                                                     int attemptNo,
                                                     String workerId,
                                                     String workerContextId,
                                                     String batchId,
                                                     TaskMessageCompatibilityState.AttemptStatus status,
                                                     TaskMessageCompatibilityState.AttemptFinalReason finalReason) {
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
