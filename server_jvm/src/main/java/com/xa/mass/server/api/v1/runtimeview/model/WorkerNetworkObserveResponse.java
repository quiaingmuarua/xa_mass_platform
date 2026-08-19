package com.xa.mass.server.api.v1.runtimeview.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkerNetworkObserveResponse(
        String endpointManagerId,
        Instant readAt,
        Map<String, String> statesByWorkerId
) {
    public WorkerNetworkObserveResponse {
        statesByWorkerId = Collections.unmodifiableMap(
                new LinkedHashMap<>(statesByWorkerId)
        );
    }
}
