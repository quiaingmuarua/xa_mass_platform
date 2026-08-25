package com.xa.mass.server.api.v1.runtimeview.model;

import org.jspecify.annotations.Nullable;

public record TaskPreviewEntry(
        String taskId,
        String scoreBand,
        @Nullable TaskView task,
        @Nullable WorkerGroupView workerGroup
) {
}
