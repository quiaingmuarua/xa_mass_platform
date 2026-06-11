package com.xa.mass.transport.route;

import java.util.Optional;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only route-owner view consumed by dispatch routing.
 *
 * <p>The input is an opaque transport {@code routeKey}. This view deliberately
 * has no worker-id reverse lookup API; worker projection scans belong to
 * inspection surfaces, not dispatch routing.</p>
 */
public interface WorkerDispatchRouteOwnerView {

    List<WorkerDispatchRouteOwner> currentOwners(String routeKey);

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

    default List<WorkerDispatchRouteOwner> activeOwners(String routeKey,
                                                       String adapterId,
                                                       String transportNodeId) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedTransportNodeId = normalizeNullable(transportNodeId);
        long now = System.currentTimeMillis();
        return currentOwners(routeKey).stream()
                .filter(owner -> owner.isActive(now))
                .filter(owner -> normalizedAdapterId == null || normalizedAdapterId.equals(owner.adapterId()))
                .filter(owner -> normalizedTransportNodeId == null
                        || normalizedTransportNodeId.equals(owner.transportNodeId()))
                .toList();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
