package com.xa.mass.engine.command;

public enum WorkerCommandLifecycleResultCode {
    ACCEPTED,
    IDEMPOTENT,
    DEFERRED,
    REJECTED,
    CONFLICT,
    NOT_FOUND,
    INVALID_TRANSITION
}
