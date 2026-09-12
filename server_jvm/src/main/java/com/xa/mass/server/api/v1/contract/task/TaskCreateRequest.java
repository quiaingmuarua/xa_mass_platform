package com.xa.mass.server.api.v1.contract.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;

public record TaskCreateRequest(
        @NotBlank String workerGroupId,
        Map<String, Object> allocationRule,
        String ruleId,
        @Min(0) @Max(99) Integer priority,
        @Positive Integer maximumCandidateWorkers,
        @Min(0) @Max(98) Integer maxRetryTimes
) {
    public TaskCreateRequest {
        priority = priority == null ? 50 : priority;
        if (maximumCandidateWorkers == null && allocationRule != null) maximumCandidateWorkers = 10;
        maxRetryTimes = maxRetryTimes == null ? 3 : maxRetryTimes;
    }
}
