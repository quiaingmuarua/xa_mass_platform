package com.xa.mass.client.worker;

import java.time.Instant;

public record WorkerStateProjection(
        String workerId,
        long stateVersion,
        String state,
        String reason,
        Instant observedAt,
        Instant acceptedAt
) {
}
