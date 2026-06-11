package com.xa.mass.transport.runtime.worker;

import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.transport.presence.WorkerDispatchRouteOwner;
import com.xa.mass.transport.presence.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;

import java.util.Objects;
import java.util.Optional;

/**
 * Selects the concrete transport route for an already matched worker.
 */
public final class WorkerDispatchRouteSelector {

    private final WorkerDispatchRouteOwnerView routeOwnerView;
    private final TransportNodeRegistry nodeRegistry;
    private final WorkerRouteKeyResolver routeKeyResolver;

    public WorkerDispatchRouteSelector(WorkerDispatchRouteOwnerView routeOwnerView,
                                       TransportNodeRegistry nodeRegistry,
                                       WorkerRouteKeyResolver routeKeyResolver) {
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.nodeRegistry = nodeRegistry;
        this.routeKeyResolver = Objects.requireNonNull(routeKeyResolver, "routeKeyResolver");
    }

    public Optional<WorkerDispatchRouteOwner> selectRoute(WorkerResourceRecord worker) {
        if (worker == null) {
            return Optional.empty();
        }
        Optional<String> routeKey = Optional.ofNullable(routeKeyResolver.resolveRouteKey(worker))
                .orElse(Optional.empty())
                .map(String::trim)
                .filter(value -> !value.isBlank());
        if (routeKey.isEmpty()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        return routeOwnerView.currentOwner(routeKey.get())
                .filter(owner -> owner.isOnline(now))
                .filter(owner -> isNodeDispatchable(owner.transportNodeId()));
    }

    private boolean isNodeDispatchable(String transportNodeId) {
        return nodeRegistry == null || nodeRegistry.isNodeOnline(transportNodeId);
    }

}
