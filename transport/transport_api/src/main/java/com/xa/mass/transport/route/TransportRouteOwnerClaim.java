package com.xa.mass.transport.route;

/**
 * Transport-owned route-consumer lease write.
 *
 * <p>{@code deliveryBucketId + workerId} is the assigned-delivery lookup key.
 * Adapter, route, and connection facts are endpoint evidence for final-hop
 * transport only.</p>
 */
public record TransportRouteOwnerClaim(String workerId,
                                       String deliveryBucketId,
                                       String adapterId,
                                       String routeKey,
                                       String connectionId,
                                       String reason) {

    public TransportRouteOwnerClaim {
        workerId = requireText(workerId, "workerId");
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        adapterId = requireText(adapterId, "adapterId");
        routeKey = requireText(routeKey, "routeKey");
        connectionId = normalizeNullable(connectionId);
        reason = normalizeNullable(reason);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
