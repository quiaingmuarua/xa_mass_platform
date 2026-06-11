package com.xa.mass.transport.route;

import java.util.Optional;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only route-owner view consumed by dispatch routing.
 *
 * <p>Dispatch calls this view only after engine selection has produced a
 * concrete worker binding. The selected worker is a delivery constraint, not a
 * scheduling or lifecycle fact. Route-key reads remain available for bounded
 * diagnostics and maintenance, while task-dispatch producer lookup should use
 * {@link #activeOwnerForSelectedWorker(String, String)}.</p>
 */
public interface WorkerDispatchRouteOwnerView {

    List<WorkerDispatchRouteOwner> currentOwners(String routeKey);

    Optional<WorkerDispatchRouteOwner> activeOwnerForSelectedWorker(String adapterId, String selectedWorkerId);

    default Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey) {
        long now = System.currentTimeMillis();
        return currentOwners(routeKey).stream()
                .filter(owner -> owner.isActive(now))
                .max(Comparator.comparingLong(WorkerDispatchRouteOwner::updatedAtEpochMillis));
    }

    default boolean hasActiveOwner(String routeKey) {
        long now = System.currentTimeMillis();
        return currentOwners(routeKey).stream().anyMatch(owner -> owner.isActive(now));
    }

    default boolean hasActiveRouteOwner(String adapterId, String routeKey) {
        if (adapterId == null || adapterId.isBlank()) {
            return false;
        }
        String normalizedAdapterId = adapterId.trim();
        long now = System.currentTimeMillis();
        return currentOwners(routeKey).stream()
                .filter(owner -> normalizedAdapterId.equals(owner.adapterId()))
                .anyMatch(owner -> owner.isActive(now));
    }
}
