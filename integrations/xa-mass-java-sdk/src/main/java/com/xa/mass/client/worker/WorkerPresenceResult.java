package com.xa.mass.client.worker;

public record WorkerPresenceResult(
        String workerId,
        String action,
        String adapterId,
        String transportHint
) {
}
