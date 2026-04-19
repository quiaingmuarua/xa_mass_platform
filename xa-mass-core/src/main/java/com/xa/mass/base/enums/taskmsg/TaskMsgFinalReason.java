package com.xa.mass.base.enums.taskmsg;

/**
 * Final reason for a logical task message.
 */
public enum TaskMsgFinalReason {
    BUSINESS_SUCCESS,
    BUSINESS_FAILED,
    TIMEOUT,
    WORKER_LOST,
    MANUAL_CANCELLED,
    LEASE_EXPIRED,
    RETRY_EXHAUSTED
}
