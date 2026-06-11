package com.xa.mass.transport.route;

import java.util.Objects;

/**
 * Read-only route owner used by engine-side dispatch routing.
 */
public record WorkerDispatchRouteOwner(String workerId,
                                       String adapterId,
                                       String routeKey,
                                       String transportNodeId,
                                       long leaseExpireAtEpochMillis,
                                       long updatedAtEpochMillis) {

    public WorkerDispatchRouteOwner {
        workerId = requireText(workerId, "workerId");
        adapterId = requireText(adapterId, "adapterId");
        routeKey = requireText(routeKey, "routeKey");
        transportNodeId = requireText(transportNodeId, "transportNodeId");
    }

    public boolean isActive(long nowEpochMillis) {
        return leaseExpireAtEpochMillis > nowEpochMillis;
    }

    public static WorkerDispatchRouteOwner fromRecord(TransportRouteOwnerRecord record) {
        Objects.requireNonNull(record, "record");
        return new WorkerDispatchRouteOwner(
                record.getWorkerId(),
                record.getAdapterId(),
                record.getRouteKey(),
                record.getTransportNodeId(),
                record.getLeaseExpireAtEpochMillis(),
                record.getUpdatedAtEpochMillis()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
