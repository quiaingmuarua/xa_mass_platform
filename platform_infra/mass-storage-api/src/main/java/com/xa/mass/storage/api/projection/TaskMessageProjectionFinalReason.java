package com.xa.mass.storage.api.projection;

/**
 * Neutral final reason for a bounded task-message projection record.
 */
public enum TaskMessageProjectionFinalReason {
    BUSINESS_SUCCESS,
    BUSINESS_FAILED,
    TIMEOUT,
    WORKER_LOST,
    MANUAL_CANCELLED,
    LEASE_EXPIRED,
    RETRY_EXHAUSTED
}
