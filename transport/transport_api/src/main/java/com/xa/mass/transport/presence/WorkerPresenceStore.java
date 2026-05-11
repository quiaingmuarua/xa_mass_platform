package com.xa.mass.transport.presence;

import java.util.List;

/**
 * Shared transport-owned worker reachability projection.
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
}
