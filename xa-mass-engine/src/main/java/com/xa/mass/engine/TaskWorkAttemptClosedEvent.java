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
        String batchId,
        TaskWorkLifecycleState.AttemptStatus status,
        TaskWorkLifecycleState.AttemptFinalReason finalReason
) {

    public static TaskWorkAttemptClosedEvent from(String taskId,
                                                     String messageId,
                                                     String attemptId,
                                                     int attemptNo,
                                                     String workerId,
                                                     String batchId,
                                                     TaskWorkLifecycleState.AttemptStatus status,
                                                     TaskWorkLifecycleState.AttemptFinalReason finalReason) {
        return new TaskWorkAttemptClosedEvent(
                taskId,
                messageId,
                attemptId,
                attemptNo,
                workerId,
                batchId,
                status,
                finalReason
        );
    }

}
