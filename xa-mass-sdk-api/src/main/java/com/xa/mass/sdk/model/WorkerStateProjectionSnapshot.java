package com.xa.mass.sdk.model;

import java.time.Instant;

public record WorkerStateProjectionSnapshot(
        String workerId,
        long stateVersion,
        String state,
        String reason,
        Instant observedAt,
        Instant acceptedAt
) {
}
