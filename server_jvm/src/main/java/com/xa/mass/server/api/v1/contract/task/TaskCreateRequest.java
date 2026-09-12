package com.xa.mass.server.api.v1.contract.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;

public record TaskCreateRequest(
        @NotBlank String workerGroupId,
        String ruleId,
        @Min(0) @Max(99) Integer priority,
        @Min(0) @Max(98) Integer maxRetryTimes
) {
    public TaskCreateRequest {
        priority = priority == null ? 50 : priority;
        maxRetryTimes = maxRetryTimes == null ? 3 : maxRetryTimes;
    }

    /** An unknown constraint must never silently turn into the default unconstrained Rule. */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported Task creation field: " + field);
    }
}
