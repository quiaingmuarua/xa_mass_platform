package com.xa.mass.transport.route;

/**
 * Durable route-consumer heartbeat evidence owned by transport.
 */
public final class TransportRouteOwnerRecord {

    private final String workerId;
    private final String deliveryBucketId;
    private final String adapterId;
    private final String routeKey;
    private final long lastHeartbeatEpochMillis;
    private final long leaseExpireAtEpochMillis;
    private final String transportInstanceId;
    private final String connectionId;
    private final long updatedAtEpochMillis;

    public TransportRouteOwnerRecord(String workerId,
                                     String deliveryBucketId,
                                     String adapterId,
                                     String routeKey,
                                     long lastHeartbeatEpochMillis,
                                     long leaseExpireAtEpochMillis,
                                     String transportInstanceId,
                                     String connectionId,
                                     long updatedAtEpochMillis) {
        this.workerId = requireNullableText(workerId);
        this.deliveryBucketId = requireNullableText(deliveryBucketId);
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

    public String getDeliveryBucketId() {
        return deliveryBucketId;
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

    public String getConsumerId() {
        return connectionId;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public boolean isLeaseActive(long nowEpochMillis) {
        return leaseExpireAtEpochMillis > nowEpochMillis;
    }

    private static String requireNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
