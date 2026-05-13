package com.xa.mass.transport.presence;

import java.util.Objects;

/**
 * Read-only route owner used by engine-side dispatch routing.
 */
public record WorkerDispatchRouteOwner(String workerId,
                                       String adapterId,
                                       String routeKey,
                                       String transportNodeId,
                                       WorkerPresenceState state,
                                       long leaseExpireAtEpochMillis,
                                       long updatedAtEpochMillis) {

    public WorkerDispatchRouteOwner {
        workerId = requireText(workerId, "workerId");
        adapterId = requireText(adapterId, "adapterId");
        routeKey = requireText(routeKey, "routeKey");
        transportNodeId = requireText(transportNodeId, "transportNodeId");
        Objects.requireNonNull(state, "state");
    }

    public boolean isOnline(long nowEpochMillis) {
        return state == WorkerPresenceState.ONLINE && leaseExpireAtEpochMillis > nowEpochMillis;
    }

    public static WorkerDispatchRouteOwner fromPresence(WorkerPresence presence) {
        Objects.requireNonNull(presence, "presence");
        return new WorkerDispatchRouteOwner(
                presence.getWorkerId(),
                presence.getAdapterId(),
                presence.getRouteKey(),
                presence.getTransportNodeId(),
                presence.getPresenceState(),
                presence.getLeaseExpireAtEpochMillis(),
                presence.getUpdatedAtEpochMillis()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
