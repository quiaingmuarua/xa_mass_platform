package com.xa.mass.transport.lease;

/**
 * Narrow policy-bearing endpoint lease evidence used to publish the current
 * selected-worker delivery consumer. This is intentionally not the ordinary
 * diagnostic view.
 */
public record TransportEndpointLeaseConsumerEvidence(String deliveryBucketId,
                                                     String workerId,
                                                     String endpointDriverId,
                                                     String endpointLeaseId,
                                                     long leaseExpireAtEpochMillis) {

    public TransportEndpointLeaseConsumerEvidence {
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        endpointLeaseId = TransportEndpointLeaseClaim.requireText(endpointLeaseId, "endpointLeaseId");
        leaseExpireAtEpochMillis = Math.max(0L, leaseExpireAtEpochMillis);
    }
}
