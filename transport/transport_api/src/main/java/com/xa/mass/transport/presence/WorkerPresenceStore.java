package com.xa.mass.transport.presence;

import java.util.Optional;

/**
 * Shared transport-owned worker reachability projection.
 *
 * <p>For worker delivery, one opaque {@code routeKey} identifies one current
 * delivery owner. Heartbeat refresh and offline transitions are owner-checked
 * operations: they only mutate a route when the incoming {@code connectionId}
 * still matches that route owner. This prevents stale disconnect or heartbeat
 * events from an older connection from revoking a newer active route.</p>
 *
 * <p>Worker-id projections are exposed through {@link WorkerPresenceInspectionView}
 * for compatibility and operator reads. Scheduling and dispatch routing must
 * use only the bounded route-owner view by {@code routeKey}.</p>
 */
public interface WorkerPresenceStore extends WorkerDispatchRouteOwnerView, WorkerPresenceInspectionView {

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

    boolean isRouteOnline(String adapterId, String routeKey);

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

    int pruneExpired();

    default long getLeaseMillis() {
        return 30_000L;
    }
}
