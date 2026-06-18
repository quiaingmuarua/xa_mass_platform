package com.xa.mass.client.worker.runtime;

public record WorkerRuntimeHeartbeatFailure(
        String workerId,
        int consecutiveFailures,
        Throwable cause
) {
}
