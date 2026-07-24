package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

public record TaskItemRequest(
        @NotBlank String messageId,
        @NotBlank String eventCode,
        @PositiveOrZero long createdAtMillis,
        @NotNull Map<String, Object> payload,
        @Min(0) @Max(10) Integer priority,
        @PositiveOrZero Long expireAtMillis,
        Map<String, Object> allocationRule
) {
    public TaskItemRequest {
        priority = priority == null ? 5 : priority;
    }
}
