package com.xa.mass.transport.presence;

import java.util.List;

/**
 * Shared transport-owned worker reachability projection.
 *
 * <p>The current presence owner is keyed by {@code workerId}. A new
 * {@link #markOnline(String, String, String, String, String)} call may replace
 * the previous owner by installing a new {@code connectionId}. Heartbeat
 * refresh and offline transitions are owner-checked operations: they only
 * mutate the stored presence when the incoming {@code connectionId} still
 * matches the current owner. This prevents stale disconnect or heartbeat
 * events from an older connection from revoking a newer active route.</p>
 */
public interface WorkerPresenceStore {

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
        return presence != null && presence.getPresenceState() == WorkerPresenceState.ONLINE;
    }

    boolean isRouteOnline(String adapterId, String routeKey);

    List<WorkerPresence> listActivePresences();

    int pruneExpired();

    default long getLeaseMillis() {
        return 30_000L;
    }
}
