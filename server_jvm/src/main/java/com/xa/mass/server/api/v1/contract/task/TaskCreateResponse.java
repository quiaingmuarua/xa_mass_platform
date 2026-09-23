package com.xa.mass.server.api.v1.contract.task;

import java.util.Objects;

public record TaskCreateResponse(
        String taskId
) {
    public TaskCreateResponse {
        Objects.requireNonNull(taskId, "taskId");
    }
}
