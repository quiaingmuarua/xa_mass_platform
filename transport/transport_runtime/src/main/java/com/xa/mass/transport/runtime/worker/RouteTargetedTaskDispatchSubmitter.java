package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.TransportDispatchFailureHandler;
import com.xa.mass.transport.runtime.TransportDispatchRouteContext;
import com.xa.mass.transport.runtime.TransportRouteKeyResolver;
import com.xa.mass.transport.runtime.dispatch.AdapterDispatchLane;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBatch;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBinding;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchHandoff;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine-side submitter that writes already assigned work into route-key owned
 * transport handoff queues.
 */
public final class RouteTargetedTaskDispatchSubmitter implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(RouteTargetedTaskDispatchSubmitter.class);

    private final RouteTargetedTaskDispatchHandoff handoff;
    private final TransportRouteKeyResolver routeKeyResolver;
    private final WorkerDispatchRouteOwnerView routeOwnerView;
    private final TransportNodeRegistry transportNodeRegistry;
    private final TransportDispatchFailureHandler failureHandler;

    public RouteTargetedTaskDispatchSubmitter(RouteTargetedTaskDispatchHandoff handoff,
                                              TransportRouteKeyResolver routeKeyResolver,
                                              WorkerDispatchRouteOwnerView routeOwnerView,
                                              TransportNodeRegistry transportNodeRegistry,
                                              TransportDispatchFailureHandler failureHandler) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.routeKeyResolver = Objects.requireNonNull(routeKeyResolver, "routeKeyResolver");
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.transportNodeRegistry = transportNodeRegistry;
        this.failureHandler = failureHandler;
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        Map<String, RouteGroup> groups = new LinkedHashMap<>();
        List<TaskDispatchBinding> unresolved = new ArrayList<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            if (binding == null) {
                continue;
            }
            String adapterId = normalize(binding.adapterId());
            if (adapterId == null) {
                unresolved.add(binding);
                continue;
            }
            String selectedWorkerId = normalize(binding.workerId());
            if (selectedWorkerId == null) {
                unresolved.add(binding);
                continue;
            }
            String routeKey = resolveRouteKey(task, binding);
            if (routeKey == null) {
                unresolved.add(binding);
                continue;
            }
            Optional<WorkerDispatchRouteOwner> selectedOwner = selectDeliveryOwner(adapterId, selectedWorkerId);
            if (selectedOwner.isEmpty()) {
                unresolved.add(binding);
                continue;
            }
            WorkerDispatchRouteOwner owner = selectedOwner.get();
            AdapterDispatchLane adapterLane = AdapterDispatchLane.forTransportNode(adapterId, owner.transportNodeId());
            RouteGroup group = groups.computeIfAbsent(
                    routeKey + "\n" + adapterLane.key(),
                    ignored -> new RouteGroup(routeKey, adapterLane)
            );
            group.deliveries.add(new RouteTargetedTaskDispatchBinding(
                    routeKey,
                    adapterLane,
                    selectedWorkerId,
                    binding
            ));
        }

        compensate(task, unresolved, "transport route consumer is unavailable after assignment");

        for (RouteGroup group : groups.values()) {
            try {
                handoff.submit(new RouteTargetedTaskDispatchBatch(
                        task,
                        group.routeKey,
                        group.adapterLane.lanePartition(),
                        group.deliveries
                ));
            } catch (RuntimeException e) {
                logger.warn("Failed to submit dispatch batch to route-key inbox: taskId={}, routeKey={}, bindings={}, reason={}",
                        task.taskId(), group.routeKey, group.deliveries.size(), e.getMessage());
                compensate(
                        task,
                        group.deliveries.stream()
                                .map(RouteTargetedTaskDispatchBinding::dispatchBinding)
                                .toList(),
                        "route-key dispatch inbox submit failed: " + e.getMessage()
                );
            }
        }
    }

    private String resolveRouteKey(TaskDispatchContext task, TaskDispatchBinding binding) {
        try {
            String routeKey = routeKeyResolver.resolveRouteKey(
                    binding,
                    TransportDispatchRouteContext.from(task, binding)
            );
            return normalize(routeKey);
        } catch (RuntimeException e) {
            logger.warn("Failed to resolve routeKey for assigned dispatch binding: taskId={}, messageId={}, reason={}",
                    task != null ? task.taskId() : null,
                    binding != null ? binding.messageId() : null,
                    e.getMessage());
            return null;
        }
    }

    private Optional<WorkerDispatchRouteOwner> selectDeliveryOwner(String adapterId, String selectedWorkerId) {
        return routeOwnerView.activeOwnerForSelectedWorker(adapterId, selectedWorkerId)
                .filter(this::isNodeUsable);
    }

    private boolean isNodeUsable(WorkerDispatchRouteOwner owner) {
        return owner != null
                && owner.transportNodeId() != null
                && !owner.transportNodeId().isBlank()
                && (transportNodeRegistry == null || transportNodeRegistry.isNodeOnline(owner.transportNodeId()));
    }

    private void compensate(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings, String detail) {
        if (dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate route-targeted dispatch failure because no failure handler is configured: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, dispatchBindings.size(), detail);
            return;
        }
        boolean compensated = failureHandler.compensate(task, List.copyOf(dispatchBindings), detail);
        if (!compensated) {
            logger.error("Route-targeted dispatch failure was not compensated: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, dispatchBindings.size(), detail);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static final class RouteGroup {
        private final String routeKey;
        private final AdapterDispatchLane adapterLane;
        private final List<RouteTargetedTaskDispatchBinding> deliveries = new ArrayList<>();

        private RouteGroup(String routeKey, AdapterDispatchLane adapterLane) {
            this.routeKey = routeKey;
            this.adapterLane = adapterLane;
        }
    }
}
