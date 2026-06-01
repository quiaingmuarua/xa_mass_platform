package com.xa.mass.worker.runtime.command;

public enum WorkerCommandLifecycleResultCode {
    ACCEPTED,
    IDEMPOTENT,
    DEFERRED,
    REJECTED,
    CONFLICT,
    NOT_FOUND,
    INVALID_TRANSITION
}
