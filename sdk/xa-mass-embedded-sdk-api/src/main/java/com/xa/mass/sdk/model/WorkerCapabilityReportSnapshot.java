package com.xa.mass.sdk.model;

public record WorkerCapabilityReportSnapshot(
        String status,
        String workerId,
        long capabilityVersion,
        boolean accepted,
        boolean snapshotChanged,
        String reason
) {
}
