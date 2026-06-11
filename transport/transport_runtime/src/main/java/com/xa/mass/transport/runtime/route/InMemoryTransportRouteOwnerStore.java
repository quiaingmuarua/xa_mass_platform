package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerInspectionView;
import com.xa.mass.transport.route.TransportRouteOwnerStore;

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
public final class InMemoryTransportRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView,
        TransportRouteOwnerInspectionView {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final String transportInstanceId;
    private final ConcurrentMap<String, TransportRouteOwnerRecord> ownerByRouteKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestRouteKeyByWorkerId = new ConcurrentHashMap<>();

    public InMemoryTransportRouteOwnerStore() {
        this(DEFAULT_LEASE_MILLIS);
    }

    public InMemoryTransportRouteOwnerStore(long leaseMillis) {
        this(leaseMillis, UUID.randomUUID().toString());
    }

    public InMemoryTransportRouteOwnerStore(long leaseMillis, String transportInstanceId) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.leaseMillis = leaseMillis;
        this.transportInstanceId = normalizeRequired(transportInstanceId, "transportInstanceId");
    }

    @Override
    public TransportRouteOwnerRecord claimRouteOwner(String workerId,
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
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
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
    public TransportRouteOwnerRecord refreshHeartbeat(String workerId,
                                           String adapterId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeNullable(routeKey);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedWorkerId == null || normalizedRouteKey == null || normalizedConnectionId == null) {
            return getLatestOwnerByWorker(workerId);
        }
        long now = System.currentTimeMillis();
        return ownerByRouteKey.compute(normalizedRouteKey, (routeKeyValue, stored) -> {
            TransportRouteOwnerRecord current = stored;
            if (current == null
                    || !normalizedWorkerId.equals(current.getWorkerId())
                    || !normalizedAdapterId.equals(current.getAdapterId())
                    || !normalizedConnectionId.equals(current.getConnectionId())) {
                return current != null ? current : stored;
            }
            TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
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
    public TransportRouteOwnerRecord releaseRouteOwner(String workerId,
                                            String adapterId,
                                            String routeKey,
                                            String connectionId,
                                            String reason) {
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        TransportRouteOwnerRecord previous = ownerByRouteKey.get(normalizedRouteKey);
        if (previous == null
                || normalizedConnectionId == null
                || !normalizedWorkerId.equals(previous.getWorkerId())
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getLatestOwnerByWorker(normalizedWorkerId);
        }
        ownerByRouteKey.remove(normalizedRouteKey, previous);
        latestRouteKeyByWorkerId.remove(normalizedWorkerId, normalizedRouteKey);
        return previous;
    }

    @Override
    public TransportRouteOwnerRecord getLatestOwnerByWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String routeKey = latestRouteKeyByWorkerId.get(normalizedWorkerId);
        TransportRouteOwnerRecord latest = routeKey != null ? ownerByRouteKey.get(routeKey) : null;
        if (latest != null && latest.isLeaseActive(now)) {
            return latest;
        }
        TransportRouteOwnerRecord newestOnline = ownerByRouteKey.values().stream()
                .filter(owner -> normalizedWorkerId.equals(owner.getWorkerId()))
                .filter(owner -> owner.isLeaseActive(now))
                .max(java.util.Comparator.comparingLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis))
                .orElse(null);
        if (newestOnline != null) {
            latestRouteKeyByWorkerId.put(normalizedWorkerId, newestOnline.getRouteKey());
            return newestOnline;
        }
        return ownerByRouteKey.values().stream()
                .filter(owner -> normalizedWorkerId.equals(owner.getWorkerId()))
                .max(java.util.Comparator.comparingLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis))
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
        TransportRouteOwnerRecord owner = ownerByRouteKey.get(normalizedRouteKey);
        return owner != null
                && normalizedAdapterId.equals(owner.getAdapterId())
                && normalizedRouteKey.equals(owner.getRouteKey())
                && owner.isLeaseActive(now);
    }

    @Override
    public Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedRouteKey == null) {
            return Optional.empty();
        }
        TransportRouteOwnerRecord owner = ownerByRouteKey.get(normalizedRouteKey);
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of(WorkerDispatchRouteOwner.fromRecord(owner));
    }

    @Override
    public List<TransportRouteOwnerRecord> listActiveRouteOwners() {
        long now = System.currentTimeMillis();
        List<TransportRouteOwnerRecord> active = new ArrayList<>();
        for (TransportRouteOwnerRecord stored : ownerByRouteKey.values()) {
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
        for (TransportRouteOwnerRecord stored : List.copyOf(ownerByRouteKey.values())) {
            if (!stored.isLeaseActive(now)) {
                ownerByRouteKey.remove(stored.getRouteKey(), stored);
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

    private TransportRouteOwnerRecord upsert(TransportRouteOwnerRecord next) {
        Objects.requireNonNull(next, "next");
        ownerByRouteKey.put(next.getRouteKey(), next);
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
