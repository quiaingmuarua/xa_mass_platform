package com.xa.mass.client.worker.session;

public record WorkerSessionConnectionFailure(
        String workerId,
        int consecutiveFailures,
        Throwable cause
) {
}
