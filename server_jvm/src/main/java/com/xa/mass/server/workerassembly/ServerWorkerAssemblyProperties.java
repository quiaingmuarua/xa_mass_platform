package com.xa.mass.server.workerassembly;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-assembly",
        ignoreUnknownFields = false
)
public record ServerWorkerAssemblyProperties(
        @DefaultValue("{}") String configJson
) {

    public ServerWorkerAssemblyProperties {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException(
                    "config-json must contain a JSON object"
            );
        }
    }
}
