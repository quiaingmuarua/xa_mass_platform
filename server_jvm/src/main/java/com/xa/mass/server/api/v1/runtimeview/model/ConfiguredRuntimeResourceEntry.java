package com.xa.mass.server.api.v1.runtimeview.model;

import org.jspecify.annotations.Nullable;

public record ConfiguredRuntimeResourceEntry(
        String workerGroupId,
        String taskId,
        @Nullable WorkerGroupView workerGroup,
        @Nullable TaskView task
) {
}
