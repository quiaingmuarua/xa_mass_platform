package com.xa.mass.transport.lease;

/**
 * Timestamp-free endpoint lease metadata for diagnostics and storage payloads.
 */
public record TransportEndpointLeaseMetadata(String deliveryBucketId,
                                             String workerId,
                                             String endpointDriverId,
                                             String sessionHandle,
                                             String endpointLeaseId,
                                             String endpointAddress) {

    public TransportEndpointLeaseMetadata {
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        sessionHandle = TransportEndpointLeaseClaim.requireText(sessionHandle, "sessionHandle");
        endpointLeaseId = TransportEndpointLeaseClaim.requireText(endpointLeaseId, "endpointLeaseId");
        endpointAddress = TransportEndpointLeaseClaim.requireText(endpointAddress, "endpointAddress");
    }
}
