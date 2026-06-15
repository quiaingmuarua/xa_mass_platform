package com.xa.mass.client.worker;

public record WorkerRegistrationResult(
        String workerId,
        String adapterNodeId,
        String workerGroupId,
        String transportHint
) {
}
