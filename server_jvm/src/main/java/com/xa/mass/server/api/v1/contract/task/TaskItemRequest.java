package com.xa.mass.server.api.v1.contract.task;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "ON_DEMAND only: {} for ANY, {workerId: [id, ...]}, "
                + "or {worker.country: [CN]} for a country-index-enabled Group (one strict [A-Z]{2} value). "
                + "At most one binding with 1..100 string parameters; omit for finite TaskItems",
                maxProperties = 1, additionalPropertiesSchema = String[].class)
        Map<String, Object> workerSelector
) {
    public TaskItemRequest {
        priority = priority == null ? 5 : priority;
    }
}
