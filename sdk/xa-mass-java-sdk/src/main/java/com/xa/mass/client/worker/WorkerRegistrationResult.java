package com.xa.mass.client.worker;

public record WorkerRegistrationResult(
        String workerId,
        String workerGroupId,
        String transportHint
) {
}
