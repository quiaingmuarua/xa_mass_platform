package com.xa.mass.engine;

public enum TaskMsgAttemptStatus {
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

    public boolean canTransitionTo(TaskMsgAttemptStatus targetStatus) {
        if (targetStatus == null || this == targetStatus) {
            return false;
        }
        return switch (this) {
            case CREATED -> targetStatus == LEASED || targetStatus == REVOKED || targetStatus == EXPIRED;
            case LEASED -> targetStatus == DISPATCHED || targetStatus == EXPIRED || targetStatus == REVOKED;
            case DISPATCHED -> targetStatus == ACKED || targetStatus == RUNNING || targetStatus == FAILED
                    || targetStatus == EXPIRED || targetStatus == REVOKED;
            case ACKED -> targetStatus == RUNNING || targetStatus == FAILED || targetStatus == EXPIRED
                    || targetStatus == REVOKED;
            case RUNNING -> targetStatus == SUCCEEDED || targetStatus == FAILED || targetStatus == EXPIRED
                    || targetStatus == REVOKED;
            case SUCCEEDED, FAILED, EXPIRED, REVOKED -> false;
        };
    }
}
