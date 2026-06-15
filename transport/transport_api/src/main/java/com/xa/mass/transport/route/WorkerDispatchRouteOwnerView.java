package com.xa.mass.transport.route;

import java.util.Optional;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only route-owner inspection view.
 *
 * <p>Route-key reads remain available for bounded diagnostics, raw-route
 * side-channels, and maintenance. Assigned task delivery must use
 * handoff-private selected-worker consumer evidence instead of this route-owner
 * view.</p>
 */
public interface WorkerDispatchRouteOwnerView {

    List<WorkerDispatchRouteOwner> currentOwners(String routeKey);

    Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(String deliveryBucketId, String selectedWorkerId);

    Optional<RouteConsumerEndpoint> endpointForSelectedWorker(String deliveryBucketId, String selectedWorkerId);

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
