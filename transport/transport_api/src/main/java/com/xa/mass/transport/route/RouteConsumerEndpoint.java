package com.xa.mass.transport.route;

/**
 * Final-hop endpoint evidence for an already selected worker.
 */
public record RouteConsumerEndpoint(String deliveryBucketId,
                                    String selectedWorkerId,
                                    String adapterId,
                                    String routeKey,
                                    String connectionId,
                                    String transportNodeId,
                                    long leaseExpireAtEpochMillis) {

    public RouteConsumerEndpoint {
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        adapterId = requireText(adapterId, "adapterId");
        routeKey = requireText(routeKey, "routeKey");
        connectionId = requireText(connectionId, "connectionId");
        transportNodeId = requireText(transportNodeId, "transportNodeId");
        leaseExpireAtEpochMillis = Math.max(0L, leaseExpireAtEpochMillis);
    }

    public boolean isActive(long nowEpochMillis) {
        return leaseExpireAtEpochMillis > nowEpochMillis;
    }

    public SelectedWorkerDeliveryTarget toTarget() {
        return new SelectedWorkerDeliveryTarget(deliveryBucketId, selectedWorkerId, transportNodeId);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
