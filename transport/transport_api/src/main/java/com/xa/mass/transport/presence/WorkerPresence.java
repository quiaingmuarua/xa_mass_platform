package com.xa.mass.transport.presence;

/**
 * Durable worker route-owner heartbeat evidence owned by transport.
 */
public final class WorkerPresence {

    private final String workerId;
    private final String adapterId;
    private final String routeKey;
    private final long lastHeartbeatEpochMillis;
    private final long leaseExpireAtEpochMillis;
    private final String transportInstanceId;
    private final String connectionId;
    private final long updatedAtEpochMillis;

    public WorkerPresence(String workerId,
                          String adapterId,
                          String routeKey,
                          long lastHeartbeatEpochMillis,
                          long leaseExpireAtEpochMillis,
                          String transportInstanceId,
                          String connectionId,
                          long updatedAtEpochMillis) {
        this.workerId = requireText(workerId, "workerId");
        this.adapterId = requireNullableText(adapterId);
        this.routeKey = requireNullableText(routeKey);
        this.lastHeartbeatEpochMillis = lastHeartbeatEpochMillis;
        this.leaseExpireAtEpochMillis = leaseExpireAtEpochMillis;
        this.transportInstanceId = requireNullableText(transportInstanceId);
        this.connectionId = requireNullableText(connectionId);
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public long getLastHeartbeatEpochMillis() {
        return lastHeartbeatEpochMillis;
    }

    public long getLeaseExpireAtEpochMillis() {
        return leaseExpireAtEpochMillis;
    }

    public String getTransportInstanceId() {
        return transportInstanceId;
    }

    public String getTransportNodeId() {
        return transportInstanceId;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public boolean isLeaseActive(long nowEpochMillis) {
        return leaseExpireAtEpochMillis > nowEpochMillis;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requireNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
