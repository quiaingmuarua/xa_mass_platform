package com.xa.mass.sdk.model;

public record WorkerStateReportSnapshot(
        String status,
        String workerId,
        long stateVersion,
        boolean accepted,
        boolean projectionChanged,
        String reason,
        WorkerStateProjectionSnapshot projection
) {
}
