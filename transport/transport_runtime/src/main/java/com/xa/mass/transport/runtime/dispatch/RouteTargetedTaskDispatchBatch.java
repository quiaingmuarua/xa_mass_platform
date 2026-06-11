package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.List;
import java.util.Objects;

/**
 * Dispatch handoff payload for one selected consumer locality.
 *
 * <p>The route key is opaque adapter metadata. The adapter dispatch lane is
 * resolved from route-owner evidence after engine assignment and owns physical
 * handoff locality; it is not route-key semantics and not a worker-selection
 * decision.</p>
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
        AdapterDispatchLane adapterLane = null;
        for (RouteTargetedTaskDispatchBinding binding : deliveryBindings) {
            if (!routeKey.equals(binding.routeKey())) {
                throw new IllegalArgumentException("delivery binding routeKey must match batch routeKey");
            }
            if (!targetTransportNodeId.equals(binding.lanePartition())) {
                throw new IllegalArgumentException("delivery binding lanePartition must match targetTransportNodeId");
            }
            if (adapterLane == null) {
                adapterLane = binding.adapterLane();
            } else if (!adapterLane.equals(binding.adapterLane())) {
                throw new IllegalArgumentException("delivery bindings must share one adapter dispatch lane");
            }
        }
    }

    public AdapterDispatchLane adapterLane() {
        return deliveryBindings.getFirst().adapterLane();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
