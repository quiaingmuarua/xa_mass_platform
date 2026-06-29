package com.xa.mass.transport.lease;

/**
 * Narrow policy-bearing endpoint lease evidence used to publish the current
 * selected-worker delivery consumer. This is intentionally not the ordinary
 * diagnostic view.
 */
public record TransportEndpointLeaseConsumerEvidence(String deliveryBucketId,
                                                     String workerId,
                                                     String endpointDriverId,
                                                     String sessionToken,
                                                     long leaseExpireAtEpochMillis) {

    public TransportEndpointLeaseConsumerEvidence {
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        sessionToken = TransportEndpointLeaseClaim.requireText(sessionToken, "sessionToken");
        leaseExpireAtEpochMillis = Math.max(0L, leaseExpireAtEpochMillis);
    }
}
