package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerDispatchRouteOwner;
import com.xa.mass.transport.presence.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceInspectionView;
import com.xa.mass.transport.presence.WorkerPresenceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory route-owner heartbeat projection.
 */
public final class InMemoryWorkerPresenceStore implements WorkerPresenceStore,
        WorkerDispatchRouteOwnerView,
        WorkerPresenceInspectionView {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final String transportInstanceId;
    private final ConcurrentMap<String, WorkerPresence> presenceByRouteKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestRouteKeyByWorkerId = new ConcurrentHashMap<>();

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
    public WorkerPresence claimRouteOwner(String workerId,
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
                now,
                now + leaseMillis,
                transportInstanceId,
                nextConnectionId,
                now
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
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeNullable(routeKey);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedWorkerId == null || normalizedRouteKey == null || normalizedConnectionId == null) {
            return getPresence(workerId);
        }
        long now = System.currentTimeMillis();
        return presenceByRouteKey.compute(normalizedRouteKey, (routeKeyValue, stored) -> {
            WorkerPresence current = stored;
            if (current == null
                    || !normalizedWorkerId.equals(current.getWorkerId())
                    || !normalizedAdapterId.equals(current.getAdapterId())
                    || !normalizedConnectionId.equals(current.getConnectionId())) {
                return current != null ? current : stored;
            }
            WorkerPresence next = new WorkerPresence(
                    current.getWorkerId(),
                    current.getAdapterId(),
                    current.getRouteKey(),
                    now,
                    now + leaseMillis,
                    transportInstanceId,
                    current.getConnectionId(),
                    now
            );
            latestRouteKeyByWorkerId.put(current.getWorkerId(), routeKeyValue);
            return next;
        });
    }

    @Override
    public WorkerPresence releaseRouteOwner(String workerId,
                                            String adapterId,
                                            String routeKey,
                                            String connectionId,
                                            String reason) {
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        WorkerPresence previous = presenceByRouteKey.get(normalizedRouteKey);
        if (previous == null
                || normalizedConnectionId == null
                || !normalizedWorkerId.equals(previous.getWorkerId())
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getPresence(normalizedWorkerId);
        }
        presenceByRouteKey.remove(normalizedRouteKey, previous);
        latestRouteKeyByWorkerId.remove(normalizedWorkerId, normalizedRouteKey);
        return previous;
    }

    @Override
    public WorkerPresence getPresence(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String routeKey = latestRouteKeyByWorkerId.get(normalizedWorkerId);
        WorkerPresence latest = routeKey != null ? presenceByRouteKey.get(routeKey) : null;
        if (latest != null && latest.isLeaseActive(now)) {
            return latest;
        }
        WorkerPresence newestOnline = presenceByRouteKey.values().stream()
                .filter(presence -> normalizedWorkerId.equals(presence.getWorkerId()))
                .filter(presence -> presence.isLeaseActive(now))
                .max(java.util.Comparator.comparingLong(WorkerPresence::getUpdatedAtEpochMillis))
                .orElse(null);
        if (newestOnline != null) {
            latestRouteKeyByWorkerId.put(normalizedWorkerId, newestOnline.getRouteKey());
            return newestOnline;
        }
        return presenceByRouteKey.values().stream()
                .filter(presence -> normalizedWorkerId.equals(presence.getWorkerId()))
                .max(java.util.Comparator.comparingLong(WorkerPresence::getUpdatedAtEpochMillis))
                .orElse(latest);
    }

    @Override
    public boolean hasActiveRouteOwner(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        WorkerPresence presence = presenceByRouteKey.get(normalizedRouteKey);
        return presence != null
                && normalizedAdapterId.equals(presence.getAdapterId())
                && normalizedRouteKey.equals(presence.getRouteKey())
                && presence.isLeaseActive(now);
    }

    @Override
    public Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedRouteKey == null) {
            return Optional.empty();
        }
        WorkerPresence presence = presenceByRouteKey.get(normalizedRouteKey);
        if (presence == null) {
            return Optional.empty();
        }
        return Optional.of(WorkerDispatchRouteOwner.fromPresence(presence));
    }

    @Override
    public List<WorkerPresence> listActivePresences() {
        long now = System.currentTimeMillis();
        List<WorkerPresence> active = new ArrayList<>();
        for (WorkerPresence stored : presenceByRouteKey.values()) {
            if (stored.isLeaseActive(now)) {
                active.add(stored);
            }
        }
        return List.copyOf(active);
    }

    @Override
    public int pruneExpired() {
        int pruned = 0;
        long now = System.currentTimeMillis();
        for (WorkerPresence stored : List.copyOf(presenceByRouteKey.values())) {
            if (!stored.isLeaseActive(now)) {
                presenceByRouteKey.remove(stored.getRouteKey(), stored);
                latestRouteKeyByWorkerId.remove(stored.getWorkerId(), stored.getRouteKey());
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

    private WorkerPresence upsert(WorkerPresence next) {
        Objects.requireNonNull(next, "next");
        presenceByRouteKey.put(next.getRouteKey(), next);
        latestRouteKeyByWorkerId.put(next.getWorkerId(), next.getRouteKey());
        return next;
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
