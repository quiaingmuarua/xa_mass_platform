package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;

import java.util.Locale;
import java.util.Objects;

/**
 * Publishes adapter session facts into transport-owned endpoint lease evidence.
 */
public final class TransportEndpointLeasePublisher {

    private final String endpointDriverId;
    private volatile TransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();

    public TransportEndpointLeasePublisher(String endpointDriverId) {
        this.endpointDriverId = requireText(endpointDriverId, "endpointDriverId").toLowerCase(Locale.ROOT);
    }

    public void setEndpointLeaseStore(TransportEndpointLeaseStore endpointLeaseStore) {
        this.endpointLeaseStore = endpointLeaseStore != null
                ? endpointLeaseStore
                : new InMemoryTransportEndpointLeaseStore();
    }

    public long getLeaseMillis() {
        return endpointLeaseStore.getLeaseMillis();
    }

    public void claim(String workerId,
                      String deliveryBucketId,
                      String endpointAddress,
                      String endpointLeaseId,
                      String reason) {
        TransportEndpointLeaseClaim claim = new TransportEndpointLeaseClaim(
                workerId,
                requireText(deliveryBucketId, "deliveryBucketId"),
                endpointDriverId,
                endpointAddress,
                endpointLeaseId,
                reason
        );
        endpointLeaseStore.claimEndpointLease(claim);
    }

    public boolean refresh(String workerId,
                           String deliveryBucketId,
                           String endpointAddress,
                           String endpointLeaseId,
                           String reason) {
        TransportEndpointLeaseHeartbeat heartbeat = new TransportEndpointLeaseHeartbeat(
                workerId,
                requireText(deliveryBucketId, "deliveryBucketId"),
                endpointDriverId,
                endpointAddress,
                endpointLeaseId,
                reason
        );
        return endpointLeaseStore.refreshEndpointLease(heartbeat).isPresent();
    }

    public boolean release(String workerId,
                           String deliveryBucketId,
                           String endpointAddress,
                           String endpointLeaseId,
                           String reason) {
        TransportEndpointLeaseRelease release = new TransportEndpointLeaseRelease(
                workerId,
                requireText(deliveryBucketId, "deliveryBucketId"),
                endpointDriverId,
                endpointAddress,
                endpointLeaseId,
                reason
        );
        boolean releasedCurrent = endpointLeaseStore.releaseEndpointLease(release);
        return releasedCurrent;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
