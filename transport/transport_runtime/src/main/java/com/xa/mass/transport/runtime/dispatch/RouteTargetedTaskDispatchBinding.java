package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;

import java.util.Objects;

/**
 * Already resolved delivery target for one assigned dispatch binding.
 */
public record RouteTargetedTaskDispatchBinding(String routeKey,
                                               AdapterDispatchLane adapterLane,
                                               String selectedWorkerId,
                                               TaskDispatchBinding dispatchBinding) {

    public RouteTargetedTaskDispatchBinding {
        routeKey = requireText(routeKey, "routeKey");
        adapterLane = Objects.requireNonNull(adapterLane, "adapterLane");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        dispatchBinding = Objects.requireNonNull(dispatchBinding, "dispatchBinding");
    }

    public String adapterId() {
        return adapterLane.adapterId();
    }

    public String lanePartition() {
        return adapterLane.lanePartition();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
