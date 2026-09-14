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
        @Schema(description = "{} selects within the bound Rule. Only worker.default accepts {workerId: [id, ...]}, scoped to its WorkerGroup. "
                + "Other query parameters belong to the Handler. Existing country queries use {worker.country: {op: eq|in, values: [CN, ...]}}; "
                + "eq requires one value and in accepts 1..100 strict [A-Z]{2} values. Named Rules reject workerId. "
                + "Omission means ANY for finite append; managed Call requires a selector object.")
        Map<String, Object> workerSelector
) {
    public TaskItemRequest {
        priority = priority == null ? 5 : priority;
    }
}
