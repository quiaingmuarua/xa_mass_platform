package com.xa.mass.runtime.worker;

public enum WorkerCapabilityReportStatus {
    ACCEPTED,
    IDEMPOTENT,
    STALE,
    CONFLICT,
    UNKNOWN_WORKER
}
