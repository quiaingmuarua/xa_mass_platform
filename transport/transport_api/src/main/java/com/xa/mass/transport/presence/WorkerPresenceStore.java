package com.xa.mass.transport.presence;

import java.util.List;

/**
 * Shared transport-owned worker reachability projection.
 *
 * <p>Presence may contain multiple active route owners for one worker. Heartbeat
 * refresh and offline transitions are owner-checked operations: they only
 * mutate a route when the incoming {@code connectionId} still matches that
 * route owner. This prevents stale disconnect or heartbeat events from an
 * older connection from revoking a newer active route.</p>
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
        return hasOnlineOwner(workerId);
    }

    boolean isRouteOnline(String adapterId, String routeKey);

    List<WorkerPresence> listActivePresences();

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
