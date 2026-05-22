package com.xa.mass.storage.api.projection;

/**
 * Neutral execution-attempt lifecycle for a bounded task-message projection record.
 */
public enum TaskMessageAttemptProjectionStatus {
    CREATED,
    LEASED,
    DISPATCHED,
    ACKED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    REVOKED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == EXPIRED || this == REVOKED;
    }

    public boolean isActive() {
        return !isFinal();
    }
}
