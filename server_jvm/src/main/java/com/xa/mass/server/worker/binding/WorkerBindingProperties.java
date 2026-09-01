package com.xa.mass.server.worker.binding;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-binding",
        ignoreUnknownFields = false
)
public record WorkerBindingProperties(
        @DefaultValue Map<String, EndpointProperties> endpoints
) {

    public WorkerBindingProperties {
        if (endpoints == null) {
            endpoints = Map.of();
        } else {
            LinkedHashMap<String, EndpointProperties> copy =
                    new LinkedHashMap<>();
            endpoints.forEach((endpointManagerId, endpoint) -> {
                requireNonBlank(endpointManagerId, "endpointManagerId");
                if (endpoint == null) {
                    throw new IllegalArgumentException(
                            "Worker endpoint config must be present"
                    );
                }
                copy.put(endpointManagerId, endpoint);
            });
            endpoints = Collections.unmodifiableMap(copy);
        }
    }

    public record EndpointProperties(
            WorkerTransportType transportType,
            URI publicUri
    ) {

        public EndpointProperties {
            if (transportType == null) {
                throw new IllegalArgumentException(
                        "transportType must be present"
                );
            }
            if (publicUri == null || !publicUri.isAbsolute()) {
                throw new IllegalArgumentException(
                        "publicUri must be absolute"
                );
            }
            String scheme = publicUri.getScheme().toLowerCase();
            boolean valid = switch (transportType) {
                case POLLING -> scheme.equals("http")
                        || scheme.equals("https");
                case WEBSOCKET -> scheme.equals("ws")
                        || scheme.equals("wss");
                case SOCKET -> scheme.equals("tcp");
            };
            if (!valid || publicUri.getHost() == null) {
                throw new IllegalArgumentException(
                        "publicUri scheme does not match transportType"
                );
            }
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
