package com.xa.mass.runtime.api;

public record WorkEnqueueOutcome(WorkEnqueueStatus status, String taskId, String messageId, boolean retryable, String reason) {

    public static WorkEnqueueOutcome enqueued(TaskWorkEnvelope item) {
        return from(WorkEnqueueStatus.ENQUEUED, item, false, null);
    }

    public static WorkEnqueueOutcome duplicate(TaskWorkEnvelope item, String reason) {
        return from(WorkEnqueueStatus.DUPLICATE, item, false, reason);
    }

    public static WorkEnqueueOutcome invalid(TaskWorkEnvelope item, String reason) {
        return from(WorkEnqueueStatus.INVALID_ITEM, item, false, reason);
    }

    public static WorkEnqueueOutcome backpressureRejected(TaskWorkEnvelope item, String reason) {
        return from(WorkEnqueueStatus.BACKPRESSURE_REJECTED, item, true, reason);
    }

    public static WorkEnqueueOutcome unavailable(TaskWorkEnvelope item, String reason) {
        return from(WorkEnqueueStatus.STORE_UNAVAILABLE, item, true, reason);
    }

    public static WorkEnqueueOutcome failed(TaskWorkEnvelope item, String reason) {
        return from(WorkEnqueueStatus.FAILED, item, true, reason);
    }

    private static WorkEnqueueOutcome from(WorkEnqueueStatus status, TaskWorkEnvelope item, boolean retryable, String reason) {
        return new WorkEnqueueOutcome(status,
                item != null ? item.taskId() : null,
                item != null ? item.messageId() : null,
                retryable,
                reason);
    }
}

