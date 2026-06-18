package com.xa.mass.client.worker.runtime;

public record WorkerRuntimePollFailure(
        String workerId,
        int consecutiveFailures,
        Throwable cause
) {
}
