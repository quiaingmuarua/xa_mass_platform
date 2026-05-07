package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;

/**
 * Built-in route-key resolver defaults for transport runtime bindings.
 */
public final class TransportRouteKeyResolvers {

    private static final TransportRouteKeyResolver WORKER_ID = (dispatchBinding, routeContext) -> {
        if (routeContext != null && routeContext.workerId() != null && !routeContext.workerId().isBlank()) {
            return routeContext.workerId();
        }
        return dispatchBinding != null ? dispatchBinding.workerId() : null;
    };

    private TransportRouteKeyResolvers() {
    }

    public static TransportRouteKeyResolver workerId() {
        return WORKER_ID;
    }
}
