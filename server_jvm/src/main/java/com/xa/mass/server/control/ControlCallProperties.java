package com.xa.mass.server.control;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("xa.mass.control-call")
public record ControlCallProperties(
        @Min(1) @Max(10_000) long defaultWaitTimeoutMillis,
        @Min(1) @Max(10_000) long maxWaitTimeoutMillis,
        @Min(1) int maxCommandsPerAdapter,
        @Min(1) int maxPendingCalls
) {
    public ControlCallProperties {
        if (defaultWaitTimeoutMillis > maxWaitTimeoutMillis) {
            throw new IllegalArgumentException(
                    "default wait timeout must not exceed maximum"
            );
        }
    }
}
