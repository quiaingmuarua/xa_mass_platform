package com.xa.mass.server.api.v1.contract.runtimeview;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public record TaskView(
        String taskId,
        String workerGroupId,
        String workerAllocationMechanism,
        String idleDisposition,
        @Nullable Map<String, Object> allocationRule,
        Map<String, String> config
) {
}
