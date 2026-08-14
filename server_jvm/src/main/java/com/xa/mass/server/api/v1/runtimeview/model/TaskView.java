package com.xa.mass.server.api.v1.runtimeview.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public record TaskView(
        String taskId,
        String workerGroupId,
        String taskType,
        @Nullable Map<String, Object> allocationRule,
        Map<String, String> config,
        @Nullable Long emptyCloseAtMillis
) {
}
