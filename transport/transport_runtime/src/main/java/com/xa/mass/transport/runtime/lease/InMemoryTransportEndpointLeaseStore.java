package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseConsumerEvidence;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseMaintenance;
import com.xa.mass.transport.lease.TransportEndpointLeaseMetadata;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory endpoint lease store keyed by delivery bucket and selected worker.
 */
public final class InMemoryTransportEndpointLeaseStore implements TransportEndpointLeaseStore,
        TransportEndpointLeaseMaintenance {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final String runtimeNodeId;
    private final ConcurrentMap<BucketWorkerKey, StoredLease> leases = new ConcurrentHashMap<>();

    public InMemoryTransportEndpointLeaseStore() {
        this(DEFAULT_LEASE_MILLIS);
    }

    public InMemoryTransportEndpointLeaseStore(long leaseMillis) {
        this(leaseMillis, UUID.randomUUID().toString());
    }

    public InMemoryTransportEndpointLeaseStore(long leaseMillis, String runtimeNodeId) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.leaseMillis = leaseMillis;
        this.runtimeNodeId = requireText(runtimeNodeId, "runtimeNodeId");
    }

    @Override
    public TransportEndpointLeaseConsumerEvidence claimEndpointLease(TransportEndpointLeaseClaim claim) {
        Objects.requireNonNull(claim, "claim");
        long deadline = System.currentTimeMillis() + leaseMillis;
        TransportEndpointLeaseMetadata metadata = metadataFrom(claim);
        leases.put(BucketWorkerKey.from(claim.deliveryBucketId(), claim.workerId()),
                new StoredLease(metadata, deadline));
        return consumerEvidence(metadata, deadline);
    }

    @Override
    public Optional<TransportEndpointLeaseConsumerEvidence> refreshEndpointLease(
            TransportEndpointLeaseHeartbeat heartbeat) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        BucketWorkerKey key = BucketWorkerKey.from(heartbeat.deliveryBucketId(), heartbeat.workerId());
        long deadline = System.currentTimeMillis() + leaseMillis;
        StoredLease next = leases.computeIfPresent(key, (ignored, current) -> {
            if (!matches(heartbeat, current.metadata())) {
                return current;
            }
            return new StoredLease(current.metadata(), deadline);
        });
        if (next == null || !matches(heartbeat, next.metadata())) {
            return Optional.empty();
        }
        return Optional.of(consumerEvidence(next.metadata(), next.leaseExpireAtEpochMillis()));
    }

    @Override
    public boolean releaseEndpointLease(TransportEndpointLeaseRelease release) {
        Objects.requireNonNull(release, "release");
        BucketWorkerKey key = BucketWorkerKey.from(release.deliveryBucketId(), release.workerId());
        StoredLease current = leases.get(key);
        if (current == null || !matches(release, current.metadata())) {
            return false;
        }
        return leases.remove(key, current);
    }

    @Override
    public Optional<TransportEndpointLeaseViewRecord> currentEndpointLease(String deliveryBucketId, String workerId) {
        BucketWorkerKey key = BucketWorkerKey.from(deliveryBucketId, workerId);
        StoredLease current = leases.get(key);
        if (current == null) {
            return Optional.empty();
        }
        if (current.leaseExpireAtEpochMillis() <= System.currentTimeMillis()) {
            leases.remove(key, current);
            return Optional.empty();
        }
        return Optional.of(new TransportEndpointLeaseViewRecord(current.metadata()));
    }

    @Override
    public int pruneExpired(String deliveryBucketId, int maxItems) {
        String normalizedBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        int limit = Math.max(0, maxItems);
        if (limit == 0) {
            return 0;
        }
        int pruned = 0;
        long now = System.currentTimeMillis();
        for (BucketWorkerKey key : List.copyOf(leases.keySet())) {
            if (pruned >= limit) {
                break;
            }
            if (!normalizedBucketId.equals(key.deliveryBucketId())) {
                continue;
            }
            StoredLease current = leases.get(key);
            if (current != null && current.leaseExpireAtEpochMillis() <= now
                    && leases.remove(key, current)) {
                pruned++;
            }
        }
        return pruned;
    }

    @Override
    public long getLeaseMillis() {
        return leaseMillis;
    }

    public String getRuntimeNodeId() {
        return runtimeNodeId;
    }

    private TransportEndpointLeaseMetadata metadataFrom(TransportEndpointLeaseClaim claim) {
        return new TransportEndpointLeaseMetadata(
                claim.deliveryBucketId(),
                claim.workerId(),
                claim.endpointDriverId(),
                runtimeNodeId,
                claim.sessionHandle(),
                claim.endpointLeaseId(),
                claim.endpointAddress()
        );
    }

    private static TransportEndpointLeaseConsumerEvidence consumerEvidence(TransportEndpointLeaseMetadata metadata,
                                                                          long leaseExpireAtEpochMillis) {
        return new TransportEndpointLeaseConsumerEvidence(
                metadata.deliveryBucketId(),
                metadata.workerId(),
                metadata.endpointDriverId(),
                metadata.endpointLeaseId(),
                leaseExpireAtEpochMillis
        );
    }

    private static boolean matches(TransportEndpointLeaseHeartbeat heartbeat,
                                   TransportEndpointLeaseMetadata metadata) {
        return heartbeat.workerId().equals(metadata.workerId())
                && heartbeat.deliveryBucketId().equals(metadata.deliveryBucketId())
                && heartbeat.endpointDriverId().equals(metadata.endpointDriverId())
                && heartbeat.endpointAddress().equals(metadata.endpointAddress())
                && heartbeat.sessionHandle().equals(metadata.sessionHandle())
                && heartbeat.endpointLeaseId().equals(metadata.endpointLeaseId());
    }

    private static boolean matches(TransportEndpointLeaseRelease release,
                                   TransportEndpointLeaseMetadata metadata) {
        return release.workerId().equals(metadata.workerId())
                && release.deliveryBucketId().equals(metadata.deliveryBucketId())
                && release.endpointDriverId().equals(metadata.endpointDriverId())
                && release.endpointAddress().equals(metadata.endpointAddress())
                && release.sessionHandle().equals(metadata.sessionHandle())
                && release.endpointLeaseId().equals(metadata.endpointLeaseId());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private record BucketWorkerKey(String deliveryBucketId, String workerId) {
        static BucketWorkerKey from(String deliveryBucketId, String workerId) {
            return new BucketWorkerKey(requireText(deliveryBucketId, "deliveryBucketId"),
                    requireText(workerId, "workerId"));
        }
    }

    private record StoredLease(TransportEndpointLeaseMetadata metadata,
                               long leaseExpireAtEpochMillis) {
    }
}
