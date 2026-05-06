package com.xa.mass.transport;

import java.util.Objects;

/**
 * Snapshot of one addressable worker endpoint.
 */
public final class WorkerEndpointSnapshot {

    private final String routeKey;
    private final String workerId;
    private final boolean active;
    private final String endpointId;
    private final String adapterId;

    public WorkerEndpointSnapshot(String routeKey,
                                  String workerId,
                                  boolean active,
                                  String endpointId,
                                  String adapterId) {
        this.routeKey = Objects.requireNonNull(routeKey, "routeKey");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.active = active;
        this.endpointId = endpointId;
        this.adapterId = adapterId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getWorkerId() {
        return workerId;
    }

    public boolean isActive() {
        return active;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    /**
     * Legacy compatibility alias for older control-surface payloads.
     *
     * <p>This returns the same value as {@link #getAdapterId()} and does not
     * represent {@code transportHint}.
     */
    public String getTransport() {
        return adapterId;
    }
}
