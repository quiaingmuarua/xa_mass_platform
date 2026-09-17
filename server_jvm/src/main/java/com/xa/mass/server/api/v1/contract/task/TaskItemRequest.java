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
        @Schema(description = "Named Matching function and its local JSON input. "
                + "Omission uses the Task refill Rule with empty input for finite append; managed Call requires an explicit query. "
                + "The function must be enabled for the Group but need not match the Task refill Rule.")
        WorkerQuery workerSelector
) {
    public TaskItemRequest {
        priority = priority == null ? 5 : priority;
    }
}
