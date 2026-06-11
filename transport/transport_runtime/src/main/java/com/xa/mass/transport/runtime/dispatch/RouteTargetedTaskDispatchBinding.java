package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;

import java.util.Objects;

/**
 * Already resolved delivery target for one assigned dispatch binding.
 */
public record RouteTargetedTaskDispatchBinding(String routeKey,
                                               String adapterId,
                                               TaskDispatchBinding dispatchBinding) {

    public RouteTargetedTaskDispatchBinding {
        routeKey = requireText(routeKey, "routeKey");
        adapterId = requireText(adapterId, "adapterId");
        dispatchBinding = Objects.requireNonNull(dispatchBinding, "dispatchBinding");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
