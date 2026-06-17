package com.xa.mass.client.worker;

public record WorkerHandlerEvidenceResult(
        String status,
        String workerId,
        long evidenceVersion,
        boolean accepted,
        boolean changed,
        String reason
) {
}
