package com.xa.mass.engine.command;

public enum WorkerCommandDeliveryStatus {
    ACCEPTED("command delivery accepted"),
    WORKER_UNAVAILABLE("worker unavailable for command delivery"),
    REJECTED("command delivery rejected"),
    FAILED("command delivery failed");

    private final String defaultReason;

    WorkerCommandDeliveryStatus(String defaultReason) {
        this.defaultReason = defaultReason;
    }

    String defaultReason() {
        return defaultReason;
    }
}
