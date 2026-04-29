package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Transport-neutral outbound message addressed to one concrete delivery route.
 *
 * <p>Concrete adapters may encode or route this message differently, but the
 * embedded runtime should not need adapter-local delivery DTOs just to place a
 * raw payload onto an adapter-owned outbound path.
 */
public final class TransportOutboundMessage {

    private final String routeKey;
    private final String rawJson;
    private final String traceId;

    public TransportOutboundMessage(String routeKey, String rawJson, String traceId) {
        this.routeKey = Objects.requireNonNull(routeKey, "routeKey");
        this.rawJson = Objects.requireNonNull(rawJson, "rawJson");
        this.traceId = traceId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getRawJson() {
        return rawJson;
    }

    public String getTraceId() {
        return traceId;
    }
}
