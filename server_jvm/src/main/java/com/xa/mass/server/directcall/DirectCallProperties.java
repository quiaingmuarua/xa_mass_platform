package com.xa.mass.server.directcall;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("xa.mass.direct-call")
public record DirectCallProperties(
        @Min(1) @Max(10_000) long defaultWaitTimeoutMillis,
        @Min(1) @Max(10_000) long maxWaitTimeoutMillis,
        @Min(1) int maxAdapterCommandsPerAdapter,
        @Min(1) int maxPendingCalls
) {
    public DirectCallProperties {
        if (defaultWaitTimeoutMillis > maxWaitTimeoutMillis) {
            throw new IllegalArgumentException(
                    "default wait timeout must not exceed maximum"
            );
        }
    }
}
