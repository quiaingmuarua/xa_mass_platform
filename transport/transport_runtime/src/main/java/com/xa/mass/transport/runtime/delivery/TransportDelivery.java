package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.Objects;

/**
 * Runtime-owned delivery record for a task dispatch handed to a transport.
 *
 * <p>This is transport delivery state, not task lifecycle state. It may move
 * between in-memory, Redis, JDBC, or another store without changing engine
 * ownership of matching, attempts, retry, release, or terminal decisions.</p>
 */
public final class TransportDelivery {

    private final String adapterId;
    private final String routeKey;
    private final TransportDispatchEnvelope envelope;

    public TransportDelivery(String adapterId,
                             String routeKey,
                             TransportDispatchEnvelope envelope) {
        this.adapterId = normalize(adapterId);
        this.routeKey = Objects.requireNonNull(routeKey, "routeKey");
        this.envelope = Objects.requireNonNull(envelope, "envelope");
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public TransportDispatchEnvelope getEnvelope() {
        return envelope;
    }

    public long getCreatedAtEpochMillis() {
        return envelope.getCreatedAtEpochMillis();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
