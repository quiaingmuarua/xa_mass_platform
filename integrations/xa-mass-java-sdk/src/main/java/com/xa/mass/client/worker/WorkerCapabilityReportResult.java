package com.xa.mass.client.worker;

public record WorkerCapabilityReportResult(
        String status,
        String workerId,
        long capabilityVersion,
        boolean accepted,
        boolean snapshotChanged,
        String reason
) {
}
