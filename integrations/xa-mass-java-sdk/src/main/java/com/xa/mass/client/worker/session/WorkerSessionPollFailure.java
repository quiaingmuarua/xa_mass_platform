package com.xa.mass.client.worker.session;

public record WorkerSessionPollFailure(
        String workerId,
        int consecutiveFailures,
        Throwable cause
) {
}
