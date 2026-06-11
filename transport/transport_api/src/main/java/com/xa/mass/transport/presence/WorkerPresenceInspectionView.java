package com.xa.mass.transport.presence;

import java.util.List;

/**
 * Worker-id projection view for operator and compatibility inspection.
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
        String normalizedWorkerId = workerId.trim();
        return listActivePresences().stream()
                .filter(presence -> normalizedWorkerId.equals(presence.getWorkerId()))
                .map(WorkerDispatchRouteOwner::fromPresence)
                .toList();
    }
}
