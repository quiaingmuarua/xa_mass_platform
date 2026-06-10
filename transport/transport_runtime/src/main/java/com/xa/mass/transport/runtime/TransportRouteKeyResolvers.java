package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.CanonicalWorkerRouteKeyCodec;

/**
 * Runtime-owned transport route-key policies shared by adapter bindings.
 */
public final class TransportRouteKeyResolvers {

    private TransportRouteKeyResolvers() {
    }

    public static TransportRouteKeyResolver canonicalWorkerSubject() {
        return (dispatchBinding, routeContext) -> {
            if (routeContext == null) {
                throw new IllegalArgumentException("routeContext must not be null");
            }
            return CanonicalWorkerRouteKeyCodec.encode(routeContext.workerGroupId(), routeContext.workerId());
        };
    }
}
