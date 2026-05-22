package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.presence.WorkerDispatchRouteOwner;
import com.xa.mass.transport.presence.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects the concrete transport route for an already matched worker.
 */
public final class WorkerDispatchRouteSelector {

    private static final Comparator<WorkerDispatchRouteOwner> NEWEST_ROUTE =
            Comparator.comparingLong(WorkerDispatchRouteOwner::updatedAtEpochMillis);

    private final WorkerDispatchRouteOwnerView routeOwnerView;
    private final TransportNodeRegistry nodeRegistry;
    private final Map<String, String> transportHintByAdapterId;

    public WorkerDispatchRouteSelector(WorkerDispatchRouteOwnerView routeOwnerView,
                                       TransportNodeRegistry nodeRegistry,
                                       Map<String, String> transportHintByAdapterId) {
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.nodeRegistry = nodeRegistry;
        this.transportHintByAdapterId = normalizeAdapterHints(transportHintByAdapterId);
    }

    public Optional<WorkerDispatchRouteOwner> selectRoute(Worker worker) {
        if (worker == null || worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        List<WorkerDispatchRouteOwner> dispatchableOwners = routeOwnerView.findOwners(worker.getWorkerId()).stream()
                .filter(owner -> owner.isOnline(now))
                .filter(owner -> isNodeDispatchable(owner.transportNodeId()))
                .toList();
        if (dispatchableOwners.isEmpty()) {
            return Optional.empty();
        }

        String workerAdapterId = normalizeAdapterId(worker.getAdapterId());
        if (workerAdapterId != null) {
            Optional<WorkerDispatchRouteOwner> exactAdapter = newest(dispatchableOwners.stream()
                    .filter(owner -> workerAdapterId.equals(normalizeAdapterId(owner.adapterId())))
                    .toList());
            if (exactAdapter.isPresent()) {
                return exactAdapter;
            }
        }

        String workerTransportHint = WorkerTransportHints.normalize(worker.getOnlineStrategy());
        if (workerTransportHint != null) {
            Optional<WorkerDispatchRouteOwner> sameFamily = newest(dispatchableOwners.stream()
                    .filter(owner -> workerTransportHint.equals(transportHintByAdapterId.get(normalizeAdapterId(owner.adapterId()))))
                    .toList());
            if (sameFamily.isPresent()) {
                return sameFamily;
            }
        }

        return newest(dispatchableOwners);
    }

    private boolean isNodeDispatchable(String transportNodeId) {
        return nodeRegistry == null || nodeRegistry.isNodeOnline(transportNodeId);
    }

    private static Optional<WorkerDispatchRouteOwner> newest(List<WorkerDispatchRouteOwner> owners) {
        return owners.stream().max(NEWEST_ROUTE);
    }

    private static Map<String, String> normalizeAdapterHints(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> normalized = new java.util.LinkedHashMap<>();
        raw.forEach((adapterId, transportHint) -> {
            String normalizedAdapterId = normalizeAdapterId(adapterId);
            String normalizedTransportHint = WorkerTransportHints.normalize(transportHint);
            if (normalizedAdapterId != null && normalizedTransportHint != null) {
                normalized.put(normalizedAdapterId, normalizedTransportHint);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
