package com.xa.mass.server.api.v1.runtimeview.model;

import java.util.List;
import java.util.Map;

public record WorkerView(
        String workerId,
        String workerGroupId,
        String endpointManagerId,
        Map<String, Object> attributes,
        Map<String, Object> platformAttributes,
        List<String> dynamicAttributeNames
) {
}
