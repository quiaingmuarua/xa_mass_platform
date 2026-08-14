package com.xa.mass.server.scenariorpc;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("xa.mass.scenario-rpc")
public record ScenarioRpcProperties(
        @NotBlank @DefaultValue("data/rpc-task") String root,
        @DefaultValue("http://127.0.0.1:18082") URI runtimeApiBaseUrl,
        @Min(1) @Max(60_000) @DefaultValue("30000")
        long waitTimeoutMillis,
        @Min(1) @Max(120_000) @DefaultValue("35000")
        long requestTimeoutMillis,
        @Min(1) @DefaultValue("1048576") int maxInputBytes,
        @Min(1) @DefaultValue("1000") int maxInputLines
) {
    public ScenarioRpcProperties {
        if (runtimeApiBaseUrl == null
                || !"http".equalsIgnoreCase(runtimeApiBaseUrl.getScheme())
                || !("127.0.0.1".equals(runtimeApiBaseUrl.getHost())
                || "localhost".equalsIgnoreCase(
                        runtimeApiBaseUrl.getHost()
                ))) {
            throw new IllegalArgumentException(
                    "runtime API base URL must be loopback HTTP"
            );
        }
        if (requestTimeoutMillis <= waitTimeoutMillis) {
            throw new IllegalArgumentException(
                    "request timeout must exceed wait timeout"
            );
        }
    }
}
