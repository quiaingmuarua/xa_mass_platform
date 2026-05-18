package com.xa.mass.engine.command;

public enum WorkerCommandStatus {
    REQUESTED,
    DELIVERY_ACCEPTED,
    EXECUTION_ACCEPTED,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
