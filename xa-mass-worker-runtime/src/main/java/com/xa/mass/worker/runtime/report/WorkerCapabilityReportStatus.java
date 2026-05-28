package com.xa.mass.worker.runtime.report;

public enum WorkerCapabilityReportStatus {
    ACCEPTED,
    IDEMPOTENT,
    STALE,
    CONFLICT,
    UNKNOWN_WORKER
}
