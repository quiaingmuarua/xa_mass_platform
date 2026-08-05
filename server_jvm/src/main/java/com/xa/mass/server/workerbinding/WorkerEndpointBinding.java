package com.xa.mass.server.workerbinding;

import java.net.URI;

public record WorkerEndpointBinding(
        String endpointManagerId,
        WorkerTransportType transportType,
        URI endpointUri
) {
}
