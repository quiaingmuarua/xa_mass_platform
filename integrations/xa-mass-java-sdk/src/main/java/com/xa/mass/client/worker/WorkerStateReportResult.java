package com.xa.mass.client.worker;

public record WorkerStateReportResult(
        String status,
        String workerId,
        long stateVersion,
        boolean accepted,
        boolean projectionChanged,
        String reason,
        WorkerStateProjection projection
) {
}
