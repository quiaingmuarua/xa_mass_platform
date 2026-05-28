package com.xa.mass.worker.runtime.report;

import java.time.Instant;
import java.util.List;

public record WorkerStateProjection(
        String workerId,
        long stateVersion,
        String state,
        String reason,
        Instant observedAt,
        Instant acceptedAt,
        List<WorkerStateReport> recentReports
) {
}
