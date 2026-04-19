package com.xa.mass.base.enums.taskmsg;

/**
 * Final reason for an execution attempt.
 */
public enum TaskMsgAttemptFinalReason {
    SUCCESS,
    BUSINESS_FAILURE,
    TIMEOUT,
    WORKER_LOST,
    MANUAL_CANCELLED,
    LEASE_EXPIRED,
    REVOKED_FOR_RETRY
}
