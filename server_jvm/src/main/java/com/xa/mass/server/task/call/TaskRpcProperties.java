package com.xa.mass.server.task.call;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("xa.mass.task-rpc")
public record TaskRpcProperties(
        @Min(1) @Max(60_000) long defaultWaitTimeoutMillis,
        @Min(1) @Max(60_000) long maxWaitTimeoutMillis,
        @Min(1) int maxWaiters,
        @Min(1) int maxPendingObservations,
        @Min(1) @Max(1_000) int maxProbeItemsPerRound,
        @Min(1) long initialProbeIntervalMillis,
        @Min(1) long normalProbeIntervalMillis,
        @Min(1) long longProbeIntervalMillis,
        java.util.Map<String, java.util.List<com.xa.mass.kernel.assignment.RefillTarget>> refillByWorkerGroup
) {
    public TaskRpcProperties {
        var captured = new java.util.LinkedHashMap<String,java.util.List<com.xa.mass.kernel.assignment.RefillTarget>>();
        if (refillByWorkerGroup != null) refillByWorkerGroup.forEach((group, targets) -> {
            if (group == null || group.isBlank() || targets == null || targets.size() > 100)
                throw new IllegalArgumentException("invalid managed Task supply");
            captured.put(group,java.util.List.copyOf(targets));
        });
        refillByWorkerGroup = java.util.Map.copyOf(captured);
        if (defaultWaitTimeoutMillis > maxWaitTimeoutMillis) {
            throw new IllegalArgumentException(
                    "default wait timeout must not exceed maximum"
            );
        }
    }
}
