package com.xa.mass.client.worker;

public record WorkerRuntimeEvidenceResult(
        String status,
        String workerId,
        long evidenceVersion,
        boolean accepted,
        boolean changed,
        String reason,
        WorkerRuntimeEvidenceSnapshot snapshot
) {
}
