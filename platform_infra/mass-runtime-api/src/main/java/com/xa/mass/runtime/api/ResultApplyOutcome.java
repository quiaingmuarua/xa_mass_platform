package com.xa.mass.runtime.api;

public record ResultApplyOutcome(ResultApplyStatus status,
                                 String taskId,
                                 String messageId,
                                 String leaseToken,
                                 boolean retryable,
                                 String reason) {

    public static ResultApplyOutcome success(TaskWorkResult result) {
        return from(ResultApplyStatus.SUCCESS_APPLIED, result, false, null);
    }

    public static ResultApplyOutcome failureFinalized(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.FAILURE_FINALIZED, result, false, reason);
    }

    public static ResultApplyOutcome retryScheduled(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.RETRY_SCHEDULED, result, true, reason);
    }

    public static ResultApplyOutcome duplicateOrLate(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.DUPLICATE_OR_LATE, result, false, reason);
    }

    public static ResultApplyOutcome staleLease(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.STALE_LEASE, result, false, reason);
    }

    public static ResultApplyOutcome noActiveLease(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.NO_ACTIVE_LEASE, result, false, reason);
    }

    public static ResultApplyOutcome invalid(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.INVALID_ITEM, result, false, reason);
    }

    public static ResultApplyOutcome failed(TaskWorkResult result, String reason) {
        return from(ResultApplyStatus.FAILED, result, true, reason);
    }

    private static ResultApplyOutcome from(ResultApplyStatus status, TaskWorkResult result, boolean retryable, String reason) {
        return new ResultApplyOutcome(status,
                result != null ? result.taskId() : null,
                result != null ? result.messageId() : null,
                result != null ? result.leaseToken() : null,
                retryable,
                reason);
    }
}

