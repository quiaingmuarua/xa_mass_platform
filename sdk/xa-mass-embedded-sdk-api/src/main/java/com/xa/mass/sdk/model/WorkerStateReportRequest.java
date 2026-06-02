package com.xa.mass.sdk.model;

import java.time.Instant;
import java.util.Map;

public record WorkerStateReportRequest(
        String workerId,
        long stateVersion,
        String state,
        String reason,
        Instant observedAt,
        Map<String, String> attributes
) {
    public WorkerStateReportRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
