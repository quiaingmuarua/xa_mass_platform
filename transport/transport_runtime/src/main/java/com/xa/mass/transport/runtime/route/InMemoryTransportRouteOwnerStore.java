package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerInspectionView;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory route-consumer heartbeat projection.
 */
public final class InMemoryTransportRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView,
        TransportRouteOwnerInspectionView {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final String transportInstanceId;
    private final ConcurrentMap<String, ConcurrentMap<String, TransportRouteOwnerRecord>> ownersByRouteKey =
            new ConcurrentHashMap<>();
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
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        String consumerId = normalizedConnectionId != null ? normalizedConnectionId : UUID.randomUUID().toString();
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                now,
                now + leaseMillis,
                transportInstanceId,
                consumerId,
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
        if (normalizedRouteKey == null || normalizedConnectionId == null) {
            return getLatestOwnerByWorker(normalizedWorkerId);
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        TransportRouteOwnerRecord current = routeConsumers != null ? routeConsumers.get(normalizedConnectionId) : null;
        if (current == null
                || !normalizedAdapterId.equals(current.getAdapterId())
                || (normalizedWorkerId != null && !normalizedWorkerId.equals(current.getWorkerId()))) {
            return current != null ? current : getLatestOwnerByWorker(normalizedWorkerId);
        }
        long now = System.currentTimeMillis();
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
        return upsert(next);
    }

    @Override
    public TransportRouteOwnerRecord releaseRouteOwner(String workerId,
                                                       String adapterId,
                                                       String routeKey,
                                                       String connectionId,
                                                       String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedConnectionId == null) {
            return getLatestOwnerByWorker(normalizedWorkerId);
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        TransportRouteOwnerRecord previous = routeConsumers != null ? routeConsumers.get(normalizedConnectionId) : null;
        if (previous == null
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || (normalizedWorkerId != null && !normalizedWorkerId.equals(previous.getWorkerId()))) {
            return getLatestOwnerByWorker(normalizedWorkerId);
        }
        removeOwner(previous);
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
        TransportRouteOwnerRecord latest = newestForWorker(normalizedWorkerId, routeKey, now, true);
        if (latest != null) {
            latestRouteKeyByWorkerId.put(normalizedWorkerId, latest.getRouteKey());
            return latest;
        }
        latest = ownersByRouteKey.values().stream()
                .flatMap(map -> map.values().stream())
                .filter(owner -> normalizedWorkerId.equals(owner.getWorkerId()))
                .filter(owner -> owner.isLeaseActive(now))
                .max(Comparator.comparingLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis))
                .orElse(null);
        if (latest != null) {
            latestRouteKeyByWorkerId.put(normalizedWorkerId, latest.getRouteKey());
            return latest;
        }
        return ownersByRouteKey.values().stream()
                .flatMap(map -> map.values().stream())
                .filter(owner -> normalizedWorkerId.equals(owner.getWorkerId()))
                .max(Comparator.comparingLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis))
                .orElse(null);
    }

    @Override
    public boolean hasActiveRouteOwner(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        return ownersByRouteKey.getOrDefault(normalizedRouteKey, new ConcurrentHashMap<>()).values().stream()
                .anyMatch(owner -> normalizedAdapterId.equals(owner.getAdapterId())
                        && owner.isLeaseActive(now));
    }

    @Override
    public List<WorkerDispatchRouteOwner> currentOwners(String routeKey) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedRouteKey == null) {
            return List.of();
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        if (routeConsumers == null || routeConsumers.isEmpty()) {
            return List.of();
        }
        return routeConsumers.values().stream()
                .map(WorkerDispatchRouteOwner::fromRecord)
                .toList();
    }

    @Override
    public List<TransportRouteOwnerRecord> listActiveRouteOwners() {
        long now = System.currentTimeMillis();
        List<TransportRouteOwnerRecord> active = new ArrayList<>();
        for (ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers : ownersByRouteKey.values()) {
            for (TransportRouteOwnerRecord stored : routeConsumers.values()) {
                if (stored.isLeaseActive(now)) {
                    active.add(stored);
                }
            }
        }
        return List.copyOf(active);
    }

    @Override
    public int pruneExpired() {
        int pruned = 0;
        long now = System.currentTimeMillis();
        for (ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers : List.copyOf(ownersByRouteKey.values())) {
            for (TransportRouteOwnerRecord stored : List.copyOf(routeConsumers.values())) {
                if (!stored.isLeaseActive(now)) {
                    removeOwner(stored);
                    pruned++;
                }
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
        ownersByRouteKey
                .computeIfAbsent(next.getRouteKey(), ignored -> new ConcurrentHashMap<>())
                .put(next.getConnectionId(), next);
        if (next.getWorkerId() != null) {
            latestRouteKeyByWorkerId.put(next.getWorkerId(), next.getRouteKey());
        }
        return next;
    }

    private void removeOwner(TransportRouteOwnerRecord owner) {
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(owner.getRouteKey());
        if (routeConsumers != null) {
            routeConsumers.remove(owner.getConnectionId(), owner);
            if (routeConsumers.isEmpty()) {
                ownersByRouteKey.remove(owner.getRouteKey(), routeConsumers);
            }
        }
        if (owner.getWorkerId() != null
                && owner.getRouteKey().equals(latestRouteKeyByWorkerId.get(owner.getWorkerId()))) {
            TransportRouteOwnerRecord latest = getLatestOwnerByWorker(owner.getWorkerId());
            if (latest == null) {
                latestRouteKeyByWorkerId.remove(owner.getWorkerId(), owner.getRouteKey());
            } else {
                latestRouteKeyByWorkerId.put(owner.getWorkerId(), latest.getRouteKey());
            }
        }
    }

    private TransportRouteOwnerRecord newestForWorker(String workerId,
                                                      String routeKey,
                                                      long now,
                                                      boolean activeOnly) {
        if (routeKey == null) {
            return null;
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(routeKey);
        if (routeConsumers == null) {
            return null;
        }
        return routeConsumers.values().stream()
                .filter(owner -> workerId.equals(owner.getWorkerId()))
                .filter(owner -> !activeOnly || owner.isLeaseActive(now))
                .max(Comparator.comparingLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis))
                .orElse(null);
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
