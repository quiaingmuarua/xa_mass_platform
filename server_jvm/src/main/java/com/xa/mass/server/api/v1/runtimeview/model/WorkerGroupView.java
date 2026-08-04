package com.xa.mass.server.api.v1.runtimeview.model;

import java.util.List;
import java.util.Map;

public record WorkerGroupView(
        String workerGroupId,
        Map<String, Object> attributes,
        List<String> eventCodes
) {
}
