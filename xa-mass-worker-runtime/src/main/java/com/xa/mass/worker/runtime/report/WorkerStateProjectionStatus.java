package com.xa.mass.worker.runtime.report;

public enum WorkerStateProjectionStatus {
    ACCEPTED,
    IDEMPOTENT,
    STALE,
    CONFLICT
}
