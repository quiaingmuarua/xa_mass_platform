package com.xa.mass.server.taskdata;

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
        @Min(1) long initialProbeIntervalMillis,
        @Min(1) long normalProbeIntervalMillis,
        @Min(1) long longProbeIntervalMillis
) {
    public TaskRpcProperties {
        if (defaultWaitTimeoutMillis > maxWaitTimeoutMillis) {
            throw new IllegalArgumentException(
                    "default wait timeout must not exceed maximum"
            );
        }
    }
}
