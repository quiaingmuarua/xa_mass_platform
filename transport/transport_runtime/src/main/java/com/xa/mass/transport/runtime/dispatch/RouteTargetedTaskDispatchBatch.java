package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.List;
import java.util.Objects;

/**
 * Route-domain dispatch handoff payload for one selected consumer locality.
 *
 * <p>The target transport node is physical drain locality resolved from
 * route-owner evidence after engine assignment. It is not route-key semantics
 * and not a worker-selection decision.</p>
 */
public record RouteTargetedTaskDispatchBatch(TaskDispatchContext task,
                                             String routeKey,
                                             String targetTransportNodeId,
                                             List<RouteTargetedTaskDispatchBinding> deliveryBindings) {

    public RouteTargetedTaskDispatchBatch {
        task = Objects.requireNonNull(task, "task");
        routeKey = requireText(routeKey, "routeKey");
        targetTransportNodeId = requireText(targetTransportNodeId, "targetTransportNodeId");
        deliveryBindings = deliveryBindings == null ? List.of() : List.copyOf(deliveryBindings);
        if (deliveryBindings.isEmpty()) {
            throw new IllegalArgumentException("deliveryBindings must not be empty");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
