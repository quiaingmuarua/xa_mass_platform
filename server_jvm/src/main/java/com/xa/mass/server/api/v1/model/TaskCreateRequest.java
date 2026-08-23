package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record TaskCreateRequest(
        @NotBlank String taskId,
        @NotBlank String workerGroupId,
        Map<String, Object> allocationRule,
        @NotNull Map<String, String> config
) {
}
