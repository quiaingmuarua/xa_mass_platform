package com.xa.mass.engine;

public enum TaskMsgStatus {
    INIT,
    ASSIGNED,
    RUNNING,
    SUCCESS,
    FAILED,
    EXPIRED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == EXPIRED;
    }

    public boolean isProcessing() {
        return this == ASSIGNED || this == RUNNING;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED || this == EXPIRED;
    }

    public boolean isRetryable() {
        return this == FAILED || this == EXPIRED;
    }

    public boolean canTransitionTo(TaskMsgStatus targetStatus) {
        if (targetStatus == null) {
            return false;
        }
        return switch (this) {
            case INIT -> targetStatus == ASSIGNED;
            case ASSIGNED -> targetStatus == RUNNING || targetStatus == FAILED || targetStatus == EXPIRED;
            case RUNNING -> targetStatus == SUCCESS || targetStatus == FAILED || targetStatus == EXPIRED;
            case SUCCESS -> false;
            case FAILED, EXPIRED -> targetStatus == INIT;
        };
    }
}
