package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ConcurrentMap<String, WorkerPresence> presenceByRouteId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestRouteIdByWorkerId = new ConcurrentHashMap<>();
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
        return upsert(next, true);
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
        String routeId = findRouteIdByConnection(normalizedWorkerId, normalizedConnectionId);
        if (routeId == null) {
            return getPresence(workerId);
        }
        return presenceByRouteId.compute(routeId, (routeIdKey, stored) -> {
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
            workerIdByRoute.put(routeIdentity(next.getAdapterId(), next.getRouteKey()), current.getWorkerId());
            latestRouteIdByWorkerId.put(current.getWorkerId(), routeIdKey);
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
        String routeId = routeIdentity(normalizedAdapterId, normalizedRouteKey);
        WorkerPresence previous = materialize(presenceByRouteId.get(routeId), now);
        if (previous == null || normalizedConnectionId == null || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getPresence(normalizedWorkerId);
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
        presenceByRouteId.put(routeId, next);
        workerIdByRoute.remove(routeIdentity(normalizedAdapterId, normalizedRouteKey), normalizedWorkerId);
        refreshLatestRouteProjection(normalizedWorkerId);
        return next;
    }

    @Override
    public WorkerPresence getPresence(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String routeId = latestRouteIdByWorkerId.get(normalizedWorkerId);
        WorkerPresence latest = routeId != null ? materialize(presenceByRouteId.get(routeId), now) : null;
        if (latest != null && latest.getPresenceState() == WorkerPresenceState.ONLINE) {
            return latest;
        }
        WorkerPresence newestOnline = presenceByRouteId.values().stream()
                .filter(presence -> normalizedWorkerId.equals(presence.getWorkerId()))
                .map(presence -> materialize(presence, now))
                .filter(Objects::nonNull)
                .filter(presence -> presence.getPresenceState() == WorkerPresenceState.ONLINE)
                .max(java.util.Comparator.comparingLong(WorkerPresence::getUpdatedAtEpochMillis))
                .orElse(null);
        if (newestOnline != null) {
            latestRouteIdByWorkerId.put(normalizedWorkerId, routeIdentity(newestOnline.getAdapterId(), newestOnline.getRouteKey()));
            return newestOnline;
        }
        return presenceByRouteId.values().stream()
                .filter(presence -> normalizedWorkerId.equals(presence.getWorkerId()))
                .map(presence -> materialize(presence, now))
                .filter(Objects::nonNull)
                .max(java.util.Comparator.comparingLong(WorkerPresence::getUpdatedAtEpochMillis))
                .orElse(latest);
    }

    @Override
    public boolean isRouteOnline(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return false;
        }
        WorkerPresence presence = materialize(presenceByRouteId.get(routeIdentity(normalizedAdapterId, normalizedRouteKey)),
                System.currentTimeMillis());
        return presence != null
                && normalizedAdapterId.equals(presence.getAdapterId())
                && normalizedRouteKey.equals(presence.getRouteKey())
                && presence.getPresenceState() == WorkerPresenceState.ONLINE;
    }

    @Override
    public List<WorkerPresence> listActivePresences() {
        long now = System.currentTimeMillis();
        List<WorkerPresence> active = new ArrayList<>();
        for (WorkerPresence stored : presenceByRouteId.values()) {
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
        for (WorkerPresence stored : List.copyOf(presenceByRouteId.values())) {
            WorkerPresence materialized = materialize(stored, now);
            if (materialized != null && materialized.getPresenceState() == WorkerPresenceState.STALE) {
                String routeId = routeIdentity(materialized.getAdapterId(), materialized.getRouteKey());
                presenceByRouteId.remove(routeId, materialized);
                latestRouteIdByWorkerId.remove(materialized.getWorkerId(), routeId);
                workerIdByRoute.remove(routeIdentity(materialized.getAdapterId(), materialized.getRouteKey()),
                        materialized.getWorkerId());
                pruned++;
            }
        }
        return pruned;
    }

    @Override
    public long getLeaseMillis() {
        return leaseMillis;
    }

    public String getTransportInstanceId() {
        return transportInstanceId;
    }

    private WorkerPresence upsert(WorkerPresence next, boolean latest) {
        Objects.requireNonNull(next, "next");
        String routeId = routeIdentity(next.getAdapterId(), next.getRouteKey());
        presenceByRouteId.compute(routeId, (ignored, previous) -> {
            workerIdByRoute.put(routeId, next.getWorkerId());
            return next;
        });
        if (latest) {
            latestRouteIdByWorkerId.put(next.getWorkerId(), routeId);
        }
        return next;
    }

    private WorkerPresence materialize(WorkerPresence stored, long now) {
        if (stored == null) {
            return null;
        }
        WorkerPresence effective = stored.effectiveAt(now);
        if (effective != stored) {
            String routeId = routeIdentity(stored.getAdapterId(), stored.getRouteKey());
            presenceByRouteId.put(routeId, effective);
            latestRouteIdByWorkerId.remove(stored.getWorkerId(), routeId);
            workerIdByRoute.remove(routeIdentity(stored.getAdapterId(), stored.getRouteKey()), stored.getWorkerId());
        }
        return effective;
    }

    private void refreshLatestRouteProjection(String workerId) {
        if (workerId == null) {
            return;
        }
        WorkerPresence next = getPresence(workerId);
        if (next == null) {
            latestRouteIdByWorkerId.remove(workerId);
        } else {
            latestRouteIdByWorkerId.put(workerId, routeIdentity(next.getAdapterId(), next.getRouteKey()));
        }
    }

    private String findRouteIdByConnection(String workerId, String connectionId) {
        for (Map.Entry<String, WorkerPresence> entry : presenceByRouteId.entrySet()) {
            WorkerPresence presence = entry.getValue();
            if (presence != null
                    && workerId.equals(presence.getWorkerId())
                    && connectionId.equals(presence.getConnectionId())) {
                return entry.getKey();
            }
        }
        return null;
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
