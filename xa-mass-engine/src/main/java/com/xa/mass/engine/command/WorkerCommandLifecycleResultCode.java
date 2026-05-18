package com.xa.mass.engine.command;

public enum WorkerCommandLifecycleResultCode {
    ACCEPTED,
    IDEMPOTENT,
    CONFLICT,
    NOT_FOUND,
    INVALID_TRANSITION
}
