package com.xa.mass.transport.lease;

/**
 * Release request for an endpoint lease. A stale release must not revoke a
 * newer lease for the same bucket/worker pair.
 */
public record TransportEndpointLeaseRelease(String workerId,
                                            String deliveryBucketId,
                                            String endpointDriverId,
                                            String endpointAddress,
                                            String sessionHandle,
                                            String endpointLeaseId,
                                            String reason) {

    public TransportEndpointLeaseRelease(String workerId,
                                         String deliveryBucketId,
                                         String endpointDriverId,
                                         String endpointAddress,
                                         String sessionHandle,
                                         String reason) {
        this(workerId,
                deliveryBucketId,
                endpointDriverId,
                endpointAddress,
                sessionHandle,
                sessionHandle,
                reason);
    }

    public TransportEndpointLeaseRelease {
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        endpointAddress = TransportEndpointLeaseClaim.requireText(endpointAddress, "endpointAddress");
        sessionHandle = TransportEndpointLeaseClaim.requireText(sessionHandle, "sessionHandle");
        endpointLeaseId = TransportEndpointLeaseClaim.requireText(endpointLeaseId, "endpointLeaseId");
        reason = TransportEndpointLeaseClaim.normalizeNullable(reason);
    }

    boolean matches(TransportEndpointLeaseMetadata metadata) {
        return metadata != null
                && workerId.equals(metadata.workerId())
                && deliveryBucketId.equals(metadata.deliveryBucketId())
                && endpointDriverId.equals(metadata.endpointDriverId())
                && endpointAddress.equals(metadata.endpointAddress())
                && sessionHandle.equals(metadata.sessionHandle())
                && endpointLeaseId.equals(metadata.endpointLeaseId());
    }
}
