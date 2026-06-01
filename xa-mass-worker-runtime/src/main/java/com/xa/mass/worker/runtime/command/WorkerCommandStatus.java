package com.xa.mass.worker.runtime.command;

public enum WorkerCommandStatus {
    REQUESTED,
    DELIVERY_ACCEPTED,
    EXECUTION_ACCEPTED,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
