package com.xa.mass.transport.runtime.dispatch;

/**
 * Typed adapter dispatch lane used after engine assignment.
 *
 * <p>The first executable lane formula is adapter id plus transport-node
 * partition sourced from endpoint evidence. The selected worker remains a
 * separate delivery constraint on each route-targeted binding.</p>
 */
public record AdapterDispatchLane(String adapterId, String lanePartition) {

    public AdapterDispatchLane {
        adapterId = requireText(adapterId, "adapterId");
        lanePartition = requireText(lanePartition, "lanePartition");
    }

    public static AdapterDispatchLane forTransportNode(String adapterId, String transportNodeId) {
        return new AdapterDispatchLane(adapterId, transportNodeId);
    }

    public String key() {
        return adapterId + "\n" + lanePartition;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
