package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

public record TaskCreateRequest(
        @NotBlank String taskId,
        @NotBlank String workerGroupId,
        @NotNull TaskType taskType,
        Map<String, Object> allocationRule,
        @NotNull Map<String, String> config,
        @PositiveOrZero Long emptyCloseAtMillis
) {
}
