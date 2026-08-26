package com.xa.mass.server.kernelpacer;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(
        prefix = "xa.mass.kernel-pacer",
        ignoreUnknownFields = false
)
public record KernelPacerProperties(
        boolean enabled,
        String configPath,
        Duration shutdownTimeout
) {
    public KernelPacerProperties {
        requireText(configPath, "configPath");
        requirePositive(shutdownTimeout, "shutdownTimeout");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
