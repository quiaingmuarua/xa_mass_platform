package com.xa.mass.transport.lease;

/**
 * Timestamp-free endpoint lease metadata for diagnostics and storage payloads.
 */
public record TransportEndpointLeaseMetadata(String deliveryBucketId,
                                             String workerId,
                                             String endpointDriverId,
                                             String sessionToken) {

    public TransportEndpointLeaseMetadata {
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        sessionToken = TransportEndpointLeaseClaim.requireText(sessionToken, "sessionToken");
    }
}
