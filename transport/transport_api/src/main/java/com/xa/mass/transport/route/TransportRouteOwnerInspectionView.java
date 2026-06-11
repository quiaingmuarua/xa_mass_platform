package com.xa.mass.transport.route;

import java.util.List;

/**
 * Worker-id projection view for SDK and operator inspection.
 *
 * <p>This view may scan active route-owner projections and must stay out of the
 * dispatch hot path. Worker delivery should use {@link WorkerDispatchRouteOwnerView}
 * with an already resolved opaque route key.</p>
 */
public interface TransportRouteOwnerInspectionView {

    TransportRouteOwnerRecord getLatestOwnerByWorker(String workerId);

    default boolean isWorkerReachable(String workerId) {
        TransportRouteOwnerRecord owner = getLatestOwnerByWorker(workerId);
        return owner != null && owner.isLeaseActive(System.currentTimeMillis());
    }

    List<TransportRouteOwnerRecord> listActiveRouteOwners();

    default List<WorkerDispatchRouteOwner> findRouteOwners(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return List.of();
        }
        TransportRouteOwnerRecord ownerRecord = getLatestOwnerByWorker(workerId.trim());
        if (ownerRecord == null) {
            return List.of();
        }
        WorkerDispatchRouteOwner owner = WorkerDispatchRouteOwner.fromRecord(ownerRecord);
        return owner.isActive(System.currentTimeMillis()) ? List.of(owner) : List.of();
    }
}
