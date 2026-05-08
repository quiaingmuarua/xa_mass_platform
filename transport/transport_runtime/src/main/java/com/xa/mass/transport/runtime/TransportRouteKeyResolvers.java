package com.xa.mass.transport.runtime;

/**
 * Runtime-owned transport route-key policies shared by adapter bindings.
 */
public final class TransportRouteKeyResolvers {

    private static final TransportRouteKeyResolver WORKER_ID =
            (dispatchBinding, routeContext) -> routeContext != null ? routeContext.workerId() : null;

    private TransportRouteKeyResolvers() {
    }

    public static TransportRouteKeyResolver workerId() {
        return WORKER_ID;
    }
}
