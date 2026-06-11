package com.xa.mass.transport.presence;

import java.util.Optional;

/**
 * Read-only route-owner view consumed by dispatch routing.
 *
 * <p>The input is an opaque transport {@code routeKey}. This view deliberately
 * has no worker-id reverse lookup API; worker projection scans belong to
 * inspection surfaces, not dispatch routing.</p>
 */
public interface WorkerDispatchRouteOwnerView {

    Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey);

    default boolean hasActiveOwner(String routeKey) {
        long now = System.currentTimeMillis();
        return currentOwner(routeKey).stream().anyMatch(owner -> owner.isActive(now));
    }

    default boolean hasActiveRouteOwner(String adapterId, String routeKey) {
        if (adapterId == null || adapterId.isBlank()) {
            return false;
        }
        String normalizedAdapterId = adapterId.trim();
        long now = System.currentTimeMillis();
        return currentOwner(routeKey)
                .filter(owner -> normalizedAdapterId.equals(owner.adapterId()))
                .filter(owner -> owner.isActive(now))
                .isPresent();
    }
}
