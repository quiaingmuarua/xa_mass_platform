package com.xa.mass.storage.api.projection;

/**
 * Neutral storage-edge status for bounded task-message projection records.
 */
public enum TaskMessageProjectionStatus {
    INIT("Initial"),
    ASSIGNED("Assigned"),
    RUNNING("Running"),
    SUCCESS("Success"),
    FAILED("Failed"),
    EXPIRED("Expired");

    private final String description;

    TaskMessageProjectionStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

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
}
