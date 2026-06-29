package com.xa.mass.transport.lease;

/**
 * Ordinary endpoint lease inspection view. It deliberately omits lease
 * timestamps; policy-bearing freshness is exposed only by claim/refresh
 * consumer evidence.
 */
public record TransportEndpointLeaseViewRecord(String deliveryBucketId,
                                               String workerId,
                                               String endpointDriverId,
                                               String sessionToken) {

    public TransportEndpointLeaseViewRecord(TransportEndpointLeaseMetadata metadata) {
        this(metadata.deliveryBucketId(),
                metadata.workerId(),
                metadata.endpointDriverId(),
                metadata.sessionToken());
    }

    public TransportEndpointLeaseViewRecord {
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        sessionToken = TransportEndpointLeaseClaim.requireText(sessionToken, "sessionToken");
    }
}
