package com.xa.mass.server.api.v1.contract.task;

import com.xa.mass.kernel.assignment.WorkerQuery;

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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Named Matching function and its local JSON input. "
                + "Required for finite append and managed Call. The Group-enabled function is independent of Task Pool supply declarations. "
                + "worker.any accepts only {} and requires explicit any Pool/function enablement and shared supply; "
                + "worker.country accepts {} or a country list; workerId accepts one identity string without Pool demand.")
        WorkerQuery workerSelector
) {
    public TaskItemRequest {
        priority = priority == null ? 5 : priority;
    }
}
