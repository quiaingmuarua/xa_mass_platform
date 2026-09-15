package com.xa.mass.server.api.v1.contract.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import com.xa.mass.workermatching.RefillTarget;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TaskCreateRequest(
        @NotBlank String workerGroupId,
        String ruleId,
        @Min(0) @Max(99) Integer priority,
        @Min(0) @Max(98) Integer maxRetryTimes,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional shared Eligibility refill targets. "
                + "Equal normalized queries merge by maximum across Tasks. Omission resolves the Group/Rule default "
                + "at binding creation, otherwise ANY 100. Only worker.default accepts workerId queries; their effective target is capped by unique ID count. Targets are not private Task quotas.")
        @Size(min=1,max=100) List<RefillTarget> refillTargets
) {
    public TaskCreateRequest {
        refillTargets = refillTargets == null ? null : List.copyOf(refillTargets);
        priority = priority == null ? 50 : priority;
        maxRetryTimes = maxRetryTimes == null ? 3 : maxRetryTimes;
    }

    /** An unknown constraint must never silently turn into the default unconstrained Rule. */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported Task creation field: " + field);
    }
}
