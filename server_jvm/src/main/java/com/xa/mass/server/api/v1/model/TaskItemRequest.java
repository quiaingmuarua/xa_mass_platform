package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;

public record TaskItemRequest(
        @NotBlank String messageId,
        @NotBlank String eventCode,
        @NotNull Map<String, Object> payload,
        @Min(0) @Max(10) Integer priority,
        @Positive Long ttlMillis,
        Map<String, Object> allocationRule
) {
    public TaskItemRequest {
        priority = priority == null ? 5 : priority;
    }
}
