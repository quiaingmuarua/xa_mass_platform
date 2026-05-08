package com.xa.mass.engine;

public enum TaskMsgFinalReason {
    BUSINESS_SUCCESS,
    BUSINESS_FAILED,
    TIMEOUT,
    WORKER_LOST,
    MANUAL_CANCELLED,
    LEASE_EXPIRED,
    RETRY_EXHAUSTED
}
