package com.xa.mass.server.workerassembly;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-assembly",
        ignoreUnknownFields = false
)
public record ServerWorkerAssemblyProperties(
        @DefaultValue("http://127.0.0.1:18082") URI runtimeApiBaseUrl,
        @DefaultValue("{}") String groupConfigJson,
        @DefaultValue("{}") String capabilityAssemblyJson,
        @DefaultValue("data/scenario-workers") String sandboxRoot
) {

    public ServerWorkerAssemblyProperties {
        requireRuntimeApiBaseUrl(runtimeApiBaseUrl);
        requireJsonObjectText(groupConfigJson, "group-config-json");
        requireJsonObjectText(
                capabilityAssemblyJson,
                "capability-assembly-json"
        );
        if (sandboxRoot == null || sandboxRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "sandbox-root must be non-blank"
            );
        }
    }

    private static void requireRuntimeApiBaseUrl(URI value) {
        Objects.requireNonNull(value, "runtime-api-base-url");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getQuery() != null
                || value.getFragment() != null
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "runtime-api-base-url must be an absolute HTTP(S) URI"
            );
        }
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
