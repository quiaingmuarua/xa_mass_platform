package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;

public record TaskCreateRequest(
        @NotBlank String workerGroupId,
        @NotNull Map<String, Object> allocationRule,
        @Min(0) @Max(99) Integer priority,
        @Positive Integer maximumCandidateWorkers,
        @Min(0) @Max(98) Integer maxRetryTimes
) {
    public TaskCreateRequest {
        priority = priority == null ? 50 : priority;
        maximumCandidateWorkers = maximumCandidateWorkers == null
                ? 10
                : maximumCandidateWorkers;
        maxRetryTimes = maxRetryTimes == null ? 3 : maxRetryTimes;
    }
}
