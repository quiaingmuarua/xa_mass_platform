package com.xa.mass.server.worker.binding;

import java.net.URI;

public record WorkerEndpointBinding(
        String endpointManagerId,
        WorkerTransportType transportType,
        URI endpointUri
) {
}
