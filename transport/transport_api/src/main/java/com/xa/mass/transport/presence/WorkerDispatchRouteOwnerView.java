package com.xa.mass.transport.presence;

import java.util.List;
import java.util.Optional;

/**
 * Read-only worker runtime route view consumed by dispatch routing.
 */
public interface WorkerDispatchRouteOwnerView {

    Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey);

    List<WorkerDispatchRouteOwner> findOwners(String workerId);

    default boolean hasOnlineOwner(String routeKey) {
        long now = System.currentTimeMillis();
        return currentOwner(routeKey).stream().anyMatch(owner -> owner.isOnline(now));
    }
}
