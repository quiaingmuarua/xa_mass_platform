package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory presence projection with lease-based online truth.
 */
public final class InMemoryWorkerPresenceStore implements WorkerPresenceStore {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final String transportInstanceId;
    private final ConcurrentMap<String, WorkerPresence> presenceByWorkerId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> workerIdByRoute = new ConcurrentHashMap<>();

    public InMemoryWorkerPresenceStore() {
        this(DEFAULT_LEASE_MILLIS);
    }

    public InMemoryWorkerPresenceStore(long leaseMillis) {
        this(leaseMillis, UUID.randomUUID().toString());
    }

    public InMemoryWorkerPresenceStore(long leaseMillis, String transportInstanceId) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.leaseMillis = leaseMillis;
        this.transportInstanceId = normalizeRequired(transportInstanceId, "transportInstanceId");
    }

    @Override
    public WorkerPresence markOnline(String workerId,
                                     String adapterId,
                                     String routeKey,
                                     String connectionId,
                                     String reason) {
        long now = System.currentTimeMillis();
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        String nextConnectionId = normalizedConnectionId != null ? normalizedConnectionId : UUID.randomUUID().toString();
        WorkerPresence next = new WorkerPresence(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                WorkerPresenceState.ONLINE,
                now,
                now + leaseMillis,
                transportInstanceId,
                nextConnectionId,
                now,
                null
        );
        return upsert(next);
    }

    @Override
    public WorkerPresence refreshHeartbeat(String workerId,
                                           String adapterId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedWorkerId == null || normalizedConnectionId == null) {
            return getPresence(workerId);
        }
        long now = System.currentTimeMillis();
        return presenceByWorkerId.compute(normalizedWorkerId, (workerIdKey, stored) -> {
            WorkerPresence current = materialize(stored, now);
            if (current == null || !normalizedConnectionId.equals(current.getConnectionId())) {
                return current != null ? current : stored;
            }
            WorkerPresence next = new WorkerPresence(
                    current.getWorkerId(),
                    current.getAdapterId(),
                    current.getRouteKey(),
                    WorkerPresenceState.ONLINE,
                    now,
                    now + leaseMillis,
                    transportInstanceId,
                    current.getConnectionId(),
                    now,
                    current.getDisconnectReason()
            );
            workerIdByRoute.put(routeIdentity(next.getAdapterId(), next.getRouteKey()), normalizedWorkerId);
            return next;
        });
    }

    @Override
    public WorkerPresence markOffline(String workerId,
                                      String adapterId,
                                      String routeKey,
                                      String connectionId,
                                      String reason) {
        long now = System.currentTimeMillis();
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        WorkerPresence previous = materialize(presenceByWorkerId.get(normalizedWorkerId), now);
        if (previous == null || normalizedConnectionId == null || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return previous;
        }
        WorkerPresence next = new WorkerPresence(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                WorkerPresenceState.OFFLINE,
                previous != null ? previous.getLastHeartbeatEpochMillis() : 0L,
                now,
                transportInstanceId,
                normalizedConnectionId,
                now,
                normalizeNullable(reason)
        );
        presenceByWorkerId.put(normalizedWorkerId, next);
        workerIdByRoute.remove(routeIdentity(normalizedAdapterId, normalizedRouteKey), normalizedWorkerId);
        return next;
    }

    @Override
    public WorkerPresence getPresence(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        return materialize(presenceByWorkerId.get(normalizedWorkerId), System.currentTimeMillis());
    }

    @Override
    public boolean isRouteOnline(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return false;
        }
        String workerId = workerIdByRoute.get(routeIdentity(normalizedAdapterId, normalizedRouteKey));
        if (workerId == null) {
            return false;
        }
        WorkerPresence presence = getPresence(workerId);
        return presence != null
                && normalizedAdapterId.equals(presence.getAdapterId())
                && normalizedRouteKey.equals(presence.getRouteKey())
                && presence.getPresenceState() == WorkerPresenceState.ONLINE;
    }

    @Override
    public List<WorkerPresence> listActivePresences() {
        long now = System.currentTimeMillis();
        List<WorkerPresence> active = new ArrayList<>();
        for (WorkerPresence stored : presenceByWorkerId.values()) {
            WorkerPresence materialized = materialize(stored, now);
            if (materialized != null && materialized.getPresenceState() == WorkerPresenceState.ONLINE) {
                active.add(materialized);
            }
        }
        return List.copyOf(active);
    }

    @Override
    public int pruneExpired() {
        int pruned = 0;
        long now = System.currentTimeMillis();
        for (WorkerPresence stored : presenceByWorkerId.values()) {
            WorkerPresence materialized = materialize(stored, now);
            if (materialized != null && materialized.getPresenceState() == WorkerPresenceState.STALE) {
                pruned++;
            }
        }
        return pruned;
    }

    public long getLeaseMillis() {
        return leaseMillis;
    }

    public String getTransportInstanceId() {
        return transportInstanceId;
    }

    private WorkerPresence upsert(WorkerPresence next) {
        Objects.requireNonNull(next, "next");
        presenceByWorkerId.compute(next.getWorkerId(), (workerId, previous) -> {
            WorkerPresence materializedPrevious = materialize(previous, System.currentTimeMillis());
            if (materializedPrevious != null) {
                workerIdByRoute.remove(routeIdentity(materializedPrevious.getAdapterId(), materializedPrevious.getRouteKey()), workerId);
            }
            workerIdByRoute.put(routeIdentity(next.getAdapterId(), next.getRouteKey()), workerId);
            return next;
        });
        return next;
    }

    private WorkerPresence materialize(WorkerPresence stored, long now) {
        if (stored == null) {
            return null;
        }
        WorkerPresence effective = stored.effectiveAt(now);
        if (effective != stored) {
            presenceByWorkerId.put(stored.getWorkerId(), effective);
            workerIdByRoute.remove(routeIdentity(stored.getAdapterId(), stored.getRouteKey()), stored.getWorkerId());
        }
        return effective;
    }

    private static String routeIdentity(String adapterId, String routeKey) {
        return adapterId + '\u0000' + routeKey;
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
