package com.xa.mass.runtime.worker;

public enum WorkerStateProjectionStatus {
    ACCEPTED,
    IDEMPOTENT,
    STALE,
    CONFLICT
}
