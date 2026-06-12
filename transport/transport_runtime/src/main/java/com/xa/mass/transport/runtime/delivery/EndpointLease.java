package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;

/**
 * Per-item endpoint evidence used to assemble a local worker dispatch frame.
 */
public record EndpointLease(String selectedWorkerId,
                            String routeKey,
                            String transportNodeId,
                            String connectionId,
                            long leaseExpireAtEpochMillis) {

    public EndpointLease {
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        routeKey = requireRouteKey(routeKey);
        transportNodeId = requireText(transportNodeId, "transportNodeId");
        connectionId = normalizeText(connectionId);
        leaseExpireAtEpochMillis = Math.max(0L, leaseExpireAtEpochMillis);
    }

    public static EndpointLease fromOwner(WorkerDispatchRouteOwner owner, String selectedWorkerId) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        return new EndpointLease(
                selectedWorkerId,
                owner.routeKey(),
                owner.transportNodeId(),
                owner.connectionId(),
                owner.leaseExpireAtEpochMillis()
        );
    }

    private static String requireRouteKey(String value) {
        String normalized = TransportDeliveryAddressing.normalizeRouteKey(value);
        if (normalized == null) {
            throw new IllegalArgumentException("routeKey must not be blank");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
