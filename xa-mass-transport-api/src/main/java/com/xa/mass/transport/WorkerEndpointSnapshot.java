package com.xa.mass.transport;

import java.util.Objects;

/**
 * Snapshot of one addressable worker endpoint.
 */
public final class WorkerEndpointSnapshot {

    private final String workerId;
    private final String endpointRole;
    private final boolean active;
    private final String endpointId;
    private final String transport;

    public WorkerEndpointSnapshot(String workerId,
                                  String endpointRole,
                                  boolean active,
                                  String endpointId,
                                  String transport) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.endpointRole = Objects.requireNonNull(endpointRole, "endpointRole");
        this.active = active;
        this.endpointId = endpointId;
        this.transport = transport;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getEndpointRole() {
        return endpointRole;
    }

    public boolean isActive() {
        return active;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getTransport() {
        return transport;
    }
}
