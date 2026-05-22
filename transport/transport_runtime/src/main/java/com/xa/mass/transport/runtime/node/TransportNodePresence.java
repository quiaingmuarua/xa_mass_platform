package com.xa.mass.transport.runtime.node;

import java.util.List;
import java.util.Objects;

public record TransportNodePresence(String transportNodeId,
                                    List<String> adapterIds,
                                    TransportNodeState state,
                                    long lastHeartbeatEpochMillis,
                                    long leaseExpireAtEpochMillis,
                                    long updatedAtEpochMillis,
                                    long connectionCount) {

    public TransportNodePresence {
        transportNodeId = requireText(transportNodeId, "transportNodeId");
        adapterIds = adapterIds == null ? List.of() : List.copyOf(adapterIds);
        Objects.requireNonNull(state, "state");
    }

    public boolean isOnline(long nowEpochMillis) {
        return state == TransportNodeState.ONLINE && leaseExpireAtEpochMillis > nowEpochMillis;
    }

    public TransportNodePresence effectiveAt(long nowEpochMillis) {
        if (state == TransportNodeState.ONLINE && leaseExpireAtEpochMillis <= nowEpochMillis) {
            return new TransportNodePresence(
                    transportNodeId,
                    adapterIds,
                    TransportNodeState.STALE,
                    lastHeartbeatEpochMillis,
                    leaseExpireAtEpochMillis,
                    updatedAtEpochMillis,
                    connectionCount
            );
        }
        return this;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
