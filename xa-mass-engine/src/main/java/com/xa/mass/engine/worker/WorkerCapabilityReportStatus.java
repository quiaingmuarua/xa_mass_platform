package com.xa.mass.engine.worker;

public enum WorkerCapabilityReportStatus {
    ACCEPTED,
    IDEMPOTENT,
    STALE,
    CONFLICT,
    UNKNOWN_WORKER
}
