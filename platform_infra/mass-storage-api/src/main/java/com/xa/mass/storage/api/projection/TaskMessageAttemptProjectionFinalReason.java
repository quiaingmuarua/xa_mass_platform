package com.xa.mass.storage.api.projection;

/**
 * Neutral final reason for a bounded execution-attempt projection record.
 */
public enum TaskMessageAttemptProjectionFinalReason {
    SUCCESS,
    BUSINESS_FAILURE,
    TIMEOUT,
    WORKER_LOST,
    MANUAL_CANCELLED,
    LEASE_EXPIRED,
    REVOKED_FOR_RETRY
}
