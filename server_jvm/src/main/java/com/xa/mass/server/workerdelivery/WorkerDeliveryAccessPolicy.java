package com.xa.mass.server.workerdelivery;

import com.xa.mass.server.workerdelivery.websocket.WorkerWebSocketProperties;

public final class WorkerDeliveryAccessPolicy {

    private final String websocketEndpointManagerId;

    public WorkerDeliveryAccessPolicy(
            WorkerWebSocketProperties websocketProperties
    ) {
        this.websocketEndpointManagerId = websocketProperties.enabled()
                ? websocketProperties.endpointManagerId()
                : null;
    }

    public void requireHttpAccess(String endpointManagerId) {
        if (websocketEndpointManagerId != null
                && websocketEndpointManagerId.equals(endpointManagerId)) {
            throw WorkerDeliveryException.invalid(
                    "WebSocket Adapter endpoint is not available over HTTP"
            );
        }
    }
}
