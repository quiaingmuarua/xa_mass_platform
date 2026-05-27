package com.xa.mass.runtime.worker;

public record WorkerCapabilityReportResult(
        WorkerCapabilityReportStatus status,
        String workerId,
        long capabilityVersion,
        boolean snapshotChanged,
        String reason
) {

    public boolean success() {
        return status == WorkerCapabilityReportStatus.ACCEPTED
                || status == WorkerCapabilityReportStatus.IDEMPOTENT;
    }
}
