package com.xa.mass.server.workerdelivery.application;

public final class WorkerDeliveryAccessPolicy {

    private final String websocketEndpointManagerId;

    public WorkerDeliveryAccessPolicy(
            String websocketEndpointManagerId
    ) {
        this.websocketEndpointManagerId = websocketEndpointManagerId;
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
