package com.xa.mass.transport.model;

/**
 * Runtime-local endpoint evidence used by a concrete adapter immediately before
 * final-hop send.
 */
public final class AdapterEndpoint {

    private final String routeKey;
    private final String transportNodeId;
    private final String connectionId;
    private final long leaseExpireAtEpochMillis;

    public AdapterEndpoint(String routeKey,
                           String transportNodeId,
                           String connectionId,
                           long leaseExpireAtEpochMillis) {
        this.routeKey = requireRouteKey(routeKey);
        this.transportNodeId = requireText(transportNodeId, "transportNodeId");
        this.connectionId = normalizeText(connectionId);
        this.leaseExpireAtEpochMillis = Math.max(0L, leaseExpireAtEpochMillis);
    }

    public String routeKey() {
        return routeKey;
    }

    public String transportNodeId() {
        return transportNodeId;
    }

    public String connectionId() {
        return connectionId;
    }

    public long leaseExpireAtEpochMillis() {
        return leaseExpireAtEpochMillis;
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
