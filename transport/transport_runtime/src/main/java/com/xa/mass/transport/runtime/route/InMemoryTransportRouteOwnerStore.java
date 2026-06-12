package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory route-consumer heartbeat projection.
 */
public final class InMemoryTransportRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final String transportInstanceId;
    private final ConcurrentMap<String, ConcurrentMap<String, TransportRouteOwnerRecord>> ownersByRouteKey =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransportRouteOwnerRecord> latestOwnerByAdapterWorker = new ConcurrentHashMap<>();

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
        return upsert(next, true);
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
            return null;
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        TransportRouteOwnerRecord current = routeConsumers != null ? routeConsumers.get(normalizedConnectionId) : null;
        if (current == null
                || !normalizedAdapterId.equals(current.getAdapterId())
                || (normalizedWorkerId != null && !normalizedWorkerId.equals(current.getWorkerId()))) {
            return current;
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
        return upsert(next, false);
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
            return null;
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        TransportRouteOwnerRecord previous = routeConsumers != null ? routeConsumers.get(normalizedConnectionId) : null;
        if (previous == null
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || (normalizedWorkerId != null && !normalizedWorkerId.equals(previous.getWorkerId()))) {
            return previous;
        }
        removeOwner(previous);
        return previous;
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
    public java.util.Optional<WorkerDispatchRouteOwner> activeOwnerForSelectedWorker(String adapterId,
                                                                                    String selectedWorkerId) {
        String key = adapterWorkerKey(adapterId, selectedWorkerId);
        if (key == null) {
            return java.util.Optional.empty();
        }
        TransportRouteOwnerRecord owner = latestOwnerByAdapterWorker.get(key);
        if (owner == null || !owner.isLeaseActive(System.currentTimeMillis())) {
            if (owner != null) {
                latestOwnerByAdapterWorker.remove(key, owner);
            }
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(WorkerDispatchRouteOwner.fromRecord(owner));
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

    private TransportRouteOwnerRecord upsert(TransportRouteOwnerRecord next, boolean replaceAdapterWorkerPointer) {
        Objects.requireNonNull(next, "next");
        ownersByRouteKey
                .computeIfAbsent(next.getRouteKey(), ignored -> new ConcurrentHashMap<>())
                .put(next.getConnectionId(), next);
        if (next.getWorkerId() != null) {
            updateAdapterWorkerPointer(next, replaceAdapterWorkerPointer);
        }
        return next;
    }

    private void updateAdapterWorkerPointer(TransportRouteOwnerRecord next, boolean replaceAdapterWorkerPointer) {
        String key = adapterWorkerKey(next.getAdapterId(), next.getWorkerId());
        if (key == null) {
            return;
        }
        if (replaceAdapterWorkerPointer) {
            latestOwnerByAdapterWorker.put(key, next);
            return;
        }
        latestOwnerByAdapterWorker.compute(key, (ignored, current) -> {
            if (current == null || sameConsumer(current, next)) {
                return next;
            }
            return current;
        });
    }

    private void removeOwner(TransportRouteOwnerRecord owner) {
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(owner.getRouteKey());
        if (routeConsumers != null) {
            routeConsumers.remove(owner.getConnectionId(), owner);
            if (routeConsumers.isEmpty()) {
                ownersByRouteKey.remove(owner.getRouteKey(), routeConsumers);
            }
        }
        String adapterWorkerKey = adapterWorkerKey(owner.getAdapterId(), owner.getWorkerId());
        if (adapterWorkerKey != null) {
            latestOwnerByAdapterWorker.remove(adapterWorkerKey, owner);
        }
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

    private static String adapterWorkerKey(String adapterId, String workerId) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedAdapterId == null || normalizedWorkerId == null) {
            return null;
        }
        return normalizedAdapterId + "\n" + normalizedWorkerId;
    }

    private static boolean sameConsumer(TransportRouteOwnerRecord left, TransportRouteOwnerRecord right) {
        return left != null
                && right != null
                && left.getRouteKey().equals(right.getRouteKey())
                && left.getConnectionId().equals(right.getConnectionId());
    }
}
