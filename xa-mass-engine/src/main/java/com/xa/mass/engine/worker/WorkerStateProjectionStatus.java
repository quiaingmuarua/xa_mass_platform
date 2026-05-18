package com.xa.mass.engine.worker;

public enum WorkerStateProjectionStatus {
    ACCEPTED,
    IDEMPOTENT,
    STALE,
    CONFLICT
}
