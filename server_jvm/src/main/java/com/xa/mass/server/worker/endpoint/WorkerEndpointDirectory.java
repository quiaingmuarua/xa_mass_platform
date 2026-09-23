package com.xa.mass.server.worker.endpoint;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The configured Endpoint directory, with one immutable address model. */
@ConfigurationProperties(prefix = "xa.mass.worker-endpoints", ignoreUnknownFields = false)
public record WorkerEndpointDirectory(
        @DefaultValue Map<String, Endpoint> endpoints,
        @DefaultValue Map<WorkerTransportType, String> defaults
) {
    public WorkerEndpointDirectory {
        endpoints = endpoints == null ? Map.of() : endpoints;
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        for (var entry : endpoints.entrySet()) {
            String id = entry.getKey();
            Endpoint endpoint = entry.getValue();
            if (id == null || id.isBlank() || endpoint == null) {
                throw new IllegalArgumentException("Endpoint ID and configuration must be present");
            }
            if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(id)
                    != (endpoint.transportType() == WorkerTransportType.POLLING)) {
                throw new IllegalArgumentException("POLLING transport must use " + SYSTEM_POLLING_ENDPOINT_MANAGER_ID);
            }
            if (!defaults.containsKey(endpoint.transportType())) {
                throw new IllegalArgumentException("Each configured transportType requires an explicit default Endpoint");
            }
        }
        endpoints = Map.copyOf(endpoints);
        for (var entry : defaults.entrySet()) {
            Endpoint endpoint = endpoints.get(entry.getValue());
            if (endpoint == null || endpoint.transportType() != entry.getKey()) {
                throw new IllegalArgumentException("Default Endpoint must exist and match transportType");
            }
        }
    }

    public String defaultEndpointId(WorkerTransportType transportType) {
        return defaults.get(transportType);
    }

    public Endpoint find(String endpointManagerId) {
        return endpoints.get(endpointManagerId);
    }

    public boolean contains(String endpointManagerId, WorkerTransportType transportType) {
        Endpoint endpoint = find(endpointManagerId);
        return endpoint != null && endpoint.transportType() == transportType;
    }

    public record Endpoint(WorkerTransportType transportType, URI publicUri) {
        public Endpoint {
            if (transportType == null || publicUri == null || !publicUri.isAbsolute()) {
                throw new IllegalArgumentException("Endpoint requires transportType and an absolute publicUri");
            }
            String scheme = publicUri.getScheme().toLowerCase();
            boolean valid = switch (transportType) {
                case POLLING -> scheme.equals("http") || scheme.equals("https");
                case WEBSOCKET -> scheme.equals("ws") || scheme.equals("wss");
                case SOCKET -> scheme.equals("tcp");
            };
            if (!valid || publicUri.getHost() == null) {
                throw new IllegalArgumentException("publicUri scheme does not match transportType");
            }
        }
    }
}
