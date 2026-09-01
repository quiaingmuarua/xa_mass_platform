package com.xa.mass.server.worker.binding;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.server.worker.binding.WorkerBindingProperties.EndpointProperties;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerEndpointDirectory {

    private final Map<String, Endpoint> endpoints;

    public WorkerEndpointDirectory(
            Map<String, EndpointProperties> configuredEndpoints
    ) {
        LinkedHashMap<String, Endpoint> copy = new LinkedHashMap<>();
        configuredEndpoints.forEach((endpointManagerId, configured) -> {
            requirePollingIdentity(endpointManagerId, configured);
            copy.put(endpointManagerId, new Endpoint(
                    endpointManagerId,
                    configured.transportType(),
                    configured.publicUri()
            ));
        });
        endpoints = Map.copyOf(copy);
    }

    public WorkerEndpointBinding select(
            String workerId,
            WorkerTransportType transportType
    ) {
        List<Endpoint> candidates = endpoints.values().stream()
                .filter(endpoint -> endpoint.transportType() == transportType)
                .sorted(Comparator.comparing(Endpoint::endpointManagerId))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        long hash = ByteBuffer.wrap(sha256(workerId)).getLong();
        Endpoint selected = candidates.get(Math.floorMod(
                hash,
                candidates.size()
        ));
        return selected.binding();
    }

    public WorkerEndpointBinding find(String endpointManagerId) {
        Endpoint endpoint = endpoints.get(endpointManagerId);
        return endpoint == null ? null : endpoint.binding();
    }

    public boolean contains(
            String endpointManagerId,
            WorkerTransportType transportType
    ) {
        Endpoint endpoint = endpoints.get(endpointManagerId);
        return endpoint != null && endpoint.transportType() == transportType;
    }

    private static void requirePollingIdentity(
            String endpointManagerId,
            EndpointProperties configured
    ) {
        boolean systemPolling = SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                endpointManagerId
        );
        boolean polling = configured.transportType()
                == WorkerTransportType.POLLING;
        if (systemPolling != polling) {
            throw new IllegalArgumentException(
                    "POLLING transport must use endpointManagerId "
                            + SYSTEM_POLLING_ENDPOINT_MANAGER_ID
            );
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record Endpoint(
            String endpointManagerId,
            WorkerTransportType transportType,
            URI endpointUri
    ) {

        private WorkerEndpointBinding binding() {
            return new WorkerEndpointBinding(
                    endpointManagerId,
                    transportType,
                    endpointUri
            );
        }
    }
}
