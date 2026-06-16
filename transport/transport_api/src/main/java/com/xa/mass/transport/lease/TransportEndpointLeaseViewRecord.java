package com.xa.mass.transport.lease;

/**
 * Ordinary endpoint lease inspection view. It deliberately omits lease
 * timestamps; policy-bearing freshness is exposed only by claim/refresh
 * consumer evidence.
 */
public record TransportEndpointLeaseViewRecord(String deliveryBucketId,
                                               String workerId,
                                               String endpointDriverId,
                                               String runtimeNodeId,
                                               String sessionHandle,
                                               String endpointLeaseId,
                                               String endpointAddress) {

    public TransportEndpointLeaseViewRecord(TransportEndpointLeaseMetadata metadata) {
        this(metadata.deliveryBucketId(),
                metadata.workerId(),
                metadata.endpointDriverId(),
                metadata.runtimeNodeId(),
                metadata.sessionHandle(),
                metadata.endpointLeaseId(),
                metadata.endpointAddress());
    }

    public TransportEndpointLeaseViewRecord {
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        runtimeNodeId = TransportEndpointLeaseClaim.requireText(runtimeNodeId, "runtimeNodeId");
        sessionHandle = TransportEndpointLeaseClaim.requireText(sessionHandle, "sessionHandle");
        endpointLeaseId = TransportEndpointLeaseClaim.requireText(endpointLeaseId, "endpointLeaseId");
        endpointAddress = TransportEndpointLeaseClaim.requireText(endpointAddress, "endpointAddress");
    }
}
