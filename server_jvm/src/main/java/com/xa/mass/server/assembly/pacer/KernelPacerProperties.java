package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(
        prefix = "xa.mass.kernel-pacer",
        ignoreUnknownFields = false
)
public record KernelPacerProperties(
        boolean enabled,
        KernelPacerRuntime.PolicyPreset preset,
        Duration shutdownTimeout
) {
    public KernelPacerProperties {
        Objects.requireNonNull(preset, "preset");
        requirePositive(shutdownTimeout, "shutdownTimeout");
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
