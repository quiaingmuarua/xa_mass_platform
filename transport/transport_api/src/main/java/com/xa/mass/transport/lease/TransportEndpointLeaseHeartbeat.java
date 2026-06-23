package com.xa.mass.transport.lease;

/**
 * Refresh request for an existing endpoint lease.
 */
public record TransportEndpointLeaseHeartbeat(String workerId,
                                              String deliveryBucketId,
                                              String endpointDriverId,
                                              String sessionHandle,
                                              String endpointLeaseId,
                                              String reason) {

    public TransportEndpointLeaseHeartbeat(String workerId,
                                           String deliveryBucketId,
                                           String endpointDriverId,
                                           String sessionHandle,
                                           String reason) {
        this(workerId,
                deliveryBucketId,
                endpointDriverId,
                sessionHandle,
                sessionHandle,
                reason);
    }

    public TransportEndpointLeaseHeartbeat {
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        sessionHandle = TransportEndpointLeaseClaim.requireText(sessionHandle, "sessionHandle");
        endpointLeaseId = TransportEndpointLeaseClaim.requireText(endpointLeaseId, "endpointLeaseId");
        reason = TransportEndpointLeaseClaim.normalizeNullable(reason);
    }

    boolean matches(TransportEndpointLeaseMetadata metadata) {
        return metadata != null
                && workerId.equals(metadata.workerId())
                && deliveryBucketId.equals(metadata.deliveryBucketId())
                && endpointDriverId.equals(metadata.endpointDriverId())
                && sessionHandle.equals(metadata.sessionHandle())
                && endpointLeaseId.equals(metadata.endpointLeaseId());
    }
}
