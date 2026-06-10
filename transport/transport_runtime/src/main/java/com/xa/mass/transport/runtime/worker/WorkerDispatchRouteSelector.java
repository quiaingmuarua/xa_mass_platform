package com.xa.mass.transport.runtime.worker;

import com.xa.mass.transport.model.CanonicalWorkerRouteKeyCodec;
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

    public WorkerDispatchRouteSelector(WorkerDispatchRouteOwnerView routeOwnerView,
                                       TransportNodeRegistry nodeRegistry) {
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.nodeRegistry = nodeRegistry;
    }

    public Optional<WorkerDispatchRouteOwner> selectRoute(WorkerResourceRecord worker) {
        if (worker == null || worker.workerId() == null || worker.workerId().isBlank()
                || worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String routeKey = CanonicalWorkerRouteKeyCodec.encode(worker.workerGroupId(), worker.workerId());
        return routeOwnerView.currentOwner(routeKey)
                .filter(owner -> owner.isOnline(now))
                .filter(owner -> isNodeDispatchable(owner.transportNodeId()));
    }

    private boolean isNodeDispatchable(String transportNodeId) {
        return nodeRegistry == null || nodeRegistry.isNodeOnline(transportNodeId);
    }

}
