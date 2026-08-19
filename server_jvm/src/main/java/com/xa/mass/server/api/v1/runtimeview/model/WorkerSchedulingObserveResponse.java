package com.xa.mass.server.api.v1.runtimeview.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkerSchedulingObserveResponse(
        String workerGroupId,
        Instant readAt,
        Map<String, String> statesByWorkerId
) {
    public WorkerSchedulingObserveResponse {
        statesByWorkerId = Collections.unmodifiableMap(
                new LinkedHashMap<>(statesByWorkerId)
        );
    }
}
