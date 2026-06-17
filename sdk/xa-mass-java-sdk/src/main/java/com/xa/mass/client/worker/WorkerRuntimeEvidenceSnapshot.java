package com.xa.mass.client.worker;

import java.time.Instant;

public record WorkerRuntimeEvidenceSnapshot(
        String workerId,
        long evidenceVersion,
        String state,
        String reason,
        Instant observedAt,
        Instant acceptedAt
) {
}
