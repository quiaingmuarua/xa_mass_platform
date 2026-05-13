package com.xa.mass.transport.presence;

import java.util.Objects;

/**
 * Durable worker presence projection owned by transport.
 */
public final class WorkerPresence {

    private final String workerId;
    private final String adapterId;
    private final String routeKey;
    private final WorkerPresenceState presenceState;
    private final long lastHeartbeatEpochMillis;
    private final long leaseExpireAtEpochMillis;
    private final String transportInstanceId;
    private final String connectionId;
    private final long updatedAtEpochMillis;
    private final String disconnectReason;

    public WorkerPresence(String workerId,
                          String adapterId,
                          String routeKey,
                          WorkerPresenceState presenceState,
                          long lastHeartbeatEpochMillis,
                          long leaseExpireAtEpochMillis,
                          String transportInstanceId,
                          String connectionId,
                          long updatedAtEpochMillis,
                          String disconnectReason) {
        this.workerId = requireText(workerId, "workerId");
        this.adapterId = requireNullableText(adapterId);
        this.routeKey = requireNullableText(routeKey);
        this.presenceState = Objects.requireNonNull(presenceState, "presenceState");
        this.lastHeartbeatEpochMillis = lastHeartbeatEpochMillis;
        this.leaseExpireAtEpochMillis = leaseExpireAtEpochMillis;
        this.transportInstanceId = requireNullableText(transportInstanceId);
        this.connectionId = requireNullableText(connectionId);
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.disconnectReason = requireNullableText(disconnectReason);
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

    public WorkerPresenceState getPresenceState() {
        return presenceState;
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

    public String getDisconnectReason() {
        return disconnectReason;
    }

    public boolean isLeaseActive(long nowEpochMillis) {
        return presenceState == WorkerPresenceState.ONLINE && leaseExpireAtEpochMillis > nowEpochMillis;
    }

    public WorkerPresence effectiveAt(long nowEpochMillis) {
        if (presenceState == WorkerPresenceState.ONLINE && leaseExpireAtEpochMillis <= nowEpochMillis) {
            return withPresenceState(WorkerPresenceState.STALE, disconnectReason);
        }
        return this;
    }

    public WorkerPresence withPresenceState(WorkerPresenceState nextState, String nextDisconnectReason) {
        return new WorkerPresence(
                workerId,
                adapterId,
                routeKey,
                nextState,
                lastHeartbeatEpochMillis,
                leaseExpireAtEpochMillis,
                transportInstanceId,
                connectionId,
                updatedAtEpochMillis,
                nextDisconnectReason
        );
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
