package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.RouteConsumerEndpoint;
import com.xa.mass.transport.route.SelectedWorkerDeliveryTarget;
import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;
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
    public TransportRouteOwnerRecord claimRouteOwner(TransportRouteOwnerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        long now = nextUpdatedAt(claim.deliveryBucketId(), claim.workerId());
        String normalizedConnectionId = normalizeNullable(claim.connectionId());
        String consumerId = normalizedConnectionId != null ? normalizedConnectionId : UUID.randomUUID().toString();
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                claim.workerId(),
                claim.deliveryBucketId(),
                claim.adapterId(),
                claim.routeKey(),
                now,
                now + leaseMillis,
                transportInstanceId,
                consumerId,
                now
        );
        return upsert(next, true);
    }

    @Override
    public TransportRouteOwnerRecord refreshHeartbeat(TransportRouteOwnerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String normalizedRouteKey = normalizeNullable(claim.routeKey());
        String normalizedConnectionId = normalizeNullable(claim.connectionId());
        if (normalizedRouteKey == null || normalizedConnectionId == null) {
            return null;
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        TransportRouteOwnerRecord current = routeConsumers != null ? routeConsumers.get(normalizedConnectionId) : null;
        if (current == null
                || !sameClaimConsumer(current, claim)
                || !isLatestSelectedWorkerConsumer(current)) {
            return current;
        }
        long now = nextUpdatedAt(current.getDeliveryBucketId(), current.getWorkerId());
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                current.getWorkerId(),
                current.getDeliveryBucketId(),
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
    public TransportRouteOwnerRecord releaseRouteOwner(TransportRouteOwnerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String normalizedRouteKey = normalizeRequired(claim.routeKey(), "routeKey");
        String normalizedConnectionId = normalizeNullable(claim.connectionId());
        if (normalizedConnectionId == null) {
            return null;
        }
        ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers = ownersByRouteKey.get(normalizedRouteKey);
        TransportRouteOwnerRecord previous = routeConsumers != null ? routeConsumers.get(normalizedConnectionId) : null;
        if (previous == null
                || !sameClaimConsumer(previous, claim)) {
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
    public java.util.Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(String deliveryBucketId,
                                                                                   String selectedWorkerId) {
        RouteConsumerEndpoint endpoint = currentEndpoint(deliveryBucketId, selectedWorkerId);
        return endpoint == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(endpoint.toTarget());
    }

    @Override
    public java.util.Optional<RouteConsumerEndpoint> endpointForSelectedWorker(String deliveryBucketId,
                                                                              String selectedWorkerId) {
        return java.util.Optional.ofNullable(currentEndpoint(deliveryBucketId, selectedWorkerId));
    }

    private RouteConsumerEndpoint currentEndpoint(String deliveryBucketId, String selectedWorkerId) {
        String normalizedBucketId = normalizeNullable(deliveryBucketId);
        String normalizedWorkerId = normalizeNullable(selectedWorkerId);
        if (normalizedBucketId == null || normalizedWorkerId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return ownersByRouteKey.values().stream()
                .flatMap(routeConsumers -> routeConsumers.values().stream())
                .filter(owner -> normalizedBucketId.equals(owner.getDeliveryBucketId()))
                .filter(owner -> normalizedWorkerId.equals(owner.getWorkerId()))
                .filter(owner -> owner.isLeaseActive(now))
                .max(Comparator.comparingLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis))
                .map(InMemoryTransportRouteOwnerStore::endpointFromRecord)
                .orElse(null);
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

    private static boolean sameClaimConsumer(TransportRouteOwnerRecord owner, TransportRouteOwnerClaim claim) {
        return owner != null
                && claim != null
                && owner.getWorkerId().equals(claim.workerId())
                && owner.getDeliveryBucketId().equals(claim.deliveryBucketId())
                && owner.getAdapterId().equals(claim.adapterId())
                && owner.getRouteKey().equals(claim.routeKey())
                && owner.getConnectionId().equals(claim.connectionId());
    }

    private boolean isLatestSelectedWorkerConsumer(TransportRouteOwnerRecord candidate) {
        if (candidate == null) {
            return false;
        }
        long candidateUpdatedAt = candidate.getUpdatedAtEpochMillis();
        for (ConcurrentMap<String, TransportRouteOwnerRecord> routeConsumers : ownersByRouteKey.values()) {
            for (TransportRouteOwnerRecord owner : routeConsumers.values()) {
                if (candidate.getDeliveryBucketId().equals(owner.getDeliveryBucketId())
                        && candidate.getWorkerId().equals(owner.getWorkerId())
                        && owner.getUpdatedAtEpochMillis() > candidateUpdatedAt) {
                    return false;
                }
            }
        }
        return true;
    }

    private long nextUpdatedAt(String deliveryBucketId, String workerId) {
        long now = System.currentTimeMillis();
        long max = ownersByRouteKey.values().stream()
                .flatMap(routeConsumers -> routeConsumers.values().stream())
                .filter(owner -> Objects.equals(deliveryBucketId, owner.getDeliveryBucketId()))
                .filter(owner -> Objects.equals(workerId, owner.getWorkerId()))
                .mapToLong(TransportRouteOwnerRecord::getUpdatedAtEpochMillis)
                .max()
                .orElse(0L);
        return Math.max(now, max + 1L);
    }

    private static RouteConsumerEndpoint endpointFromRecord(TransportRouteOwnerRecord owner) {
        return new RouteConsumerEndpoint(
                owner.getDeliveryBucketId(),
                owner.getWorkerId(),
                owner.getAdapterId(),
                owner.getRouteKey(),
                owner.getConnectionId(),
                owner.getTransportNodeId(),
                owner.getLeaseExpireAtEpochMillis()
        );
    }
}
