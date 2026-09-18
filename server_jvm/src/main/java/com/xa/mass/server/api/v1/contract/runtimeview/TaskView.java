package com.xa.mass.server.api.v1.contract.runtimeview;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public record TaskView(
        String taskId,
        String projectId,
        String workerGroupId,
        String idleDisposition,
        java.util.List<com.xa.mass.kernel.assignment.RefillTarget> refill,
        Map<String, String> config
) {
}
