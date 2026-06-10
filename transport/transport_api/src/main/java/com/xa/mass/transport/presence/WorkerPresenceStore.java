package com.xa.mass.transport.presence;

import java.util.List;
import java.util.Optional;

/**
 * Shared transport-owned worker reachability projection.
 *
 * <p>For worker delivery, one canonical {@code routeKey} identifies one current
 * delivery owner. Heartbeat refresh and offline transitions are owner-checked
 * operations: they only mutate a route when the incoming {@code connectionId}
 * still matches that route owner. This prevents stale disconnect or heartbeat
 * events from an older connection from revoking a newer active route.</p>
 *
 * <p>Worker-id projections such as {@link #getPresence(String)} and
 * {@link #findOwners(String)} are compatibility/operator views. Scheduling and
 * dispatch routing must use the bounded route-owner view by canonical
 * {@code routeKey}.</p>
 */
public interface WorkerPresenceStore extends WorkerDispatchRouteOwnerView {

    WorkerPresence markOnline(String workerId,
                              String adapterId,
                              String routeKey,
                              String connectionId,
                              String reason);

    WorkerPresence refreshHeartbeat(String workerId,
                                    String adapterId,
                                    String routeKey,
                                    String connectionId,
                                    String reason);

    WorkerPresence markOffline(String workerId,
                               String adapterId,
                               String routeKey,
                               String connectionId,
                               String reason);

    WorkerPresence getPresence(String workerId);

    default boolean isWorkerOnline(String workerId) {
        WorkerPresence presence = getPresence(workerId);
        return presence != null && presence.isLeaseActive(System.currentTimeMillis());
    }

    boolean isRouteOnline(String adapterId, String routeKey);

    List<WorkerPresence> listActivePresences();

    @Override
    default Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return Optional.empty();
        }
        String normalizedRouteKey = routeKey.trim();
        return listActivePresences().stream()
                .filter(presence -> normalizedRouteKey.equals(presence.getRouteKey()))
                .map(WorkerDispatchRouteOwner::fromPresence)
                .findFirst();
    }

    @Override
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

    int pruneExpired();

    default long getLeaseMillis() {
        return 30_000L;
    }
}
