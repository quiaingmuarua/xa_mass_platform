package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseConsumerEvidence;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerClaim;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;

import java.util.Locale;
import java.util.Objects;

/**
 * Publishes adapter session facts into transport-owned endpoint lease evidence.
 */
public final class TransportEndpointLeasePublisher {

    private final String endpointDriverId;
    private volatile TransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
    private volatile DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry =
            NoopDeliveryCommandConsumerRegistry.INSTANCE;

    public TransportEndpointLeasePublisher(String endpointDriverId) {
        this.endpointDriverId = requireText(endpointDriverId, "endpointDriverId").toLowerCase(Locale.ROOT);
    }

    public void setEndpointLeaseStore(TransportEndpointLeaseStore endpointLeaseStore) {
        this.endpointLeaseStore = endpointLeaseStore != null
                ? endpointLeaseStore
                : new InMemoryTransportEndpointLeaseStore();
    }

    public void setDeliveryCommandConsumerRegistry(DeliveryCommandConsumerRegistry registry) {
        this.deliveryCommandConsumerRegistry = registry != null
                ? registry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
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
        claimDeliveryConsumer(endpointLeaseStore.claimEndpointLease(claim));
    }

    public void refresh(String workerId,
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
        endpointLeaseStore.refreshEndpointLease(heartbeat).ifPresentOrElse(
                this::claimDeliveryConsumer,
                () -> releaseDeliveryConsumer(heartbeat)
        );
    }

    public void release(String workerId,
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
        endpointLeaseStore.releaseEndpointLease(release);
        releaseDeliveryConsumer(release);
    }

    private void claimDeliveryConsumer(TransportEndpointLeaseConsumerEvidence evidence) {
        deliveryCommandConsumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                evidence.deliveryBucketId(),
                evidence.workerId(),
                evidence.endpointLeaseId(),
                evidence.leaseExpireAtEpochMillis()
        ));
    }

    private void releaseDeliveryConsumer(TransportEndpointLeaseRelease release) {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                release.deliveryBucketId(),
                release.workerId(),
                release.endpointLeaseId(),
                0L
        ));
    }

    private void releaseDeliveryConsumer(TransportEndpointLeaseHeartbeat heartbeat) {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                heartbeat.deliveryBucketId(),
                heartbeat.workerId(),
                heartbeat.endpointLeaseId(),
                0L
        ));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
