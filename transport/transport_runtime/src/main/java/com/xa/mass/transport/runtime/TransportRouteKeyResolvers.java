package com.xa.mass.transport.runtime;

import com.xa.mass.engine.listener.TaskDispatchBinding;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskDispatchRuntimeMetadata;

/**
 * Built-in route-key resolver defaults for transport runtime bindings.
 */
public final class TransportRouteKeyResolvers {

    private static final TransportRouteKeyResolver WORKER_ID = (dispatchBinding, payload) -> {
        if (payload != null) {
            TaskDispatchRuntimeMetadata metadata = payload.runtimeMetadata();
            if (metadata != null) {
                return metadata.workerId();
            }
        }
        return dispatchBinding != null && dispatchBinding.attempt() != null
                ? dispatchBinding.attempt().getWorkerId()
                : null;
    };

    private TransportRouteKeyResolvers() {
    }

    public static TransportRouteKeyResolver workerId() {
        return WORKER_ID;
    }
}
