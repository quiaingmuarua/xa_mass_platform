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
        String pythonExecutable,
        String workingDirectory,
        String configPath,
        String stateDirectory,
        Duration startupTimeout,
        Duration shutdownTimeout
) {
    public KernelPacerProperties {
        requireText(pythonExecutable, "pythonExecutable");
        requireText(workingDirectory, "workingDirectory");
        requireText(configPath, "configPath");
        requireText(stateDirectory, "stateDirectory");
        requirePositive(startupTimeout, "startupTimeout");
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
