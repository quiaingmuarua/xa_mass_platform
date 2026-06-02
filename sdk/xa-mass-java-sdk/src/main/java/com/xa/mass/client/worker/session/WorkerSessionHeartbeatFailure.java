package com.xa.mass.client.worker.session;

public record WorkerSessionHeartbeatFailure(
        String workerId,
        int consecutiveFailures,
        Throwable cause
) {
}
