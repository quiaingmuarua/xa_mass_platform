package com.xa.mass.transport.presence;

import java.util.List;

/**
 * Read-only worker runtime route view consumed by dispatch routing.
 */
public interface WorkerDispatchRouteOwnerView {

    List<WorkerDispatchRouteOwner> findOwners(String workerId);

    default boolean hasOnlineOwner(String workerId) {
        long now = System.currentTimeMillis();
        return findOwners(workerId).stream().anyMatch(owner -> owner.isOnline(now));
    }
}
