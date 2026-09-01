package com.xa.mass.server.assembly.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-assembly",
        ignoreUnknownFields = false
)
public record ServerWorkerAssemblyProperties(
        @DefaultValue("{}") String groupConfigJson
) {

    public ServerWorkerAssemblyProperties {
        requireJsonObjectText(groupConfigJson, "group-config-json");
    }

    private static void requireJsonObjectText(
            String value,
            String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must contain a JSON object"
            );
        }
    }
}
