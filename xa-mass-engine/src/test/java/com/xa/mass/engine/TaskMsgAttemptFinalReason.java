package com.xa.mass.engine;

public enum TaskMsgAttemptFinalReason {
    SUCCESS,
    BUSINESS_FAILURE,
    TIMEOUT,
    WORKER_LOST,
    MANUAL_CANCELLED,
    LEASE_EXPIRED,
    REVOKED_FOR_RETRY
}
