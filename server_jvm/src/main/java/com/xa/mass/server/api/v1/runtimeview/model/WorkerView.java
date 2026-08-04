package com.xa.mass.server.api.v1.runtimeview.model;

import java.util.Map;

public record WorkerView(
        String workerId,
        String workerGroupId,
        String endpointManagerId,
        Map<String, Object> workerProperties,
        Map<String, Object> platformProperties
) {
}
