package com.xa.mass.server.kernelbinding;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xa.mass.kernel")
public record PythonKernelProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
    public PythonKernelProperties {
        Objects.requireNonNull(baseUrl, "baseUrl");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        if (!"http".equalsIgnoreCase(baseUrl.getScheme())
                && !"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "baseUrl must use http or https"
            );
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
