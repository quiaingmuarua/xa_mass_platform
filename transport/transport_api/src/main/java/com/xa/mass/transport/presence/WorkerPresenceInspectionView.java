package com.xa.mass.transport.presence;

import java.util.List;

/**
 * Worker-id projection view for SDK and operator inspection.
 *
 * <p>This view may scan active presence projections and must stay out of the
 * dispatch hot path. Worker delivery should use {@link WorkerDispatchRouteOwnerView}
 * with an already resolved opaque route key.</p>
 */
public interface WorkerPresenceInspectionView {

    WorkerPresence getPresence(String workerId);

    default boolean isWorkerOnline(String workerId) {
        WorkerPresence presence = getPresence(workerId);
        return presence != null && presence.isLeaseActive(System.currentTimeMillis());
    }

    List<WorkerPresence> listActivePresences();

    default List<WorkerDispatchRouteOwner> findOwners(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return List.of();
        }
        WorkerPresence presence = getPresence(workerId.trim());
        if (presence == null) {
            return List.of();
        }
        WorkerDispatchRouteOwner owner = WorkerDispatchRouteOwner.fromPresence(presence);
        return owner.isActive(System.currentTimeMillis()) ? List.of(owner) : List.of();
    }
}
