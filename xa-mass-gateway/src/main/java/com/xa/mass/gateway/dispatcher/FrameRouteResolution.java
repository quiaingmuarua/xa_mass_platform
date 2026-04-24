package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;

import java.util.Objects;

/**
 * Immutable gateway frame-route resolution result.
 */
public final class FrameRouteResolution {

    private static final FrameRouteResolution NOT_FOUND = new FrameRouteResolution(null, "none");

    private final MassMessageHandler handler;
    private final String routeKey;

    private FrameRouteResolution(MassMessageHandler handler, String routeKey) {
        this.handler = handler;
        this.routeKey = routeKey;
    }

    public static FrameRouteResolution matched(MassMessageHandler handler, String routeKey) {
        return new FrameRouteResolution(
                Objects.requireNonNull(handler, "handler"),
                Objects.requireNonNull(routeKey, "routeKey")
        );
    }

    public static FrameRouteResolution notFound() {
        return NOT_FOUND;
    }

    public MassMessageHandler getHandler() {
        return handler;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public boolean isMatched() {
        return handler != null;
    }

    public boolean isNotFound() {
        return handler == null;
    }

    @Override
    public String toString() {
        return "FrameRouteResolution{"
                + "routeKey='" + routeKey + '\''
                + ", matched=" + isMatched()
                + '}';
    }
}
