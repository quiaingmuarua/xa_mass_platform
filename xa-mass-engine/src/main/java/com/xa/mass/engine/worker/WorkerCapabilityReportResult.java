package com.xa.mass.engine.worker;

public record WorkerCapabilityReportResult(
        WorkerCapabilityReportStatus status,
        String workerId,
        long capabilityVersion,
        boolean snapshotChanged,
        WorkerRegistrySnapshot snapshot,
        String reason
) {

    public boolean success() {
        return status == WorkerCapabilityReportStatus.ACCEPTED
                || status == WorkerCapabilityReportStatus.IDEMPOTENT;
    }
}
