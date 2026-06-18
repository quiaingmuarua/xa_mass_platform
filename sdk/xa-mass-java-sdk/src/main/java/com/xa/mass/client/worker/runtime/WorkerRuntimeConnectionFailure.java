package com.xa.mass.client.worker.runtime;

public record WorkerRuntimeConnectionFailure(
        String workerId,
        int consecutiveFailures,
        Throwable cause
) {
}
