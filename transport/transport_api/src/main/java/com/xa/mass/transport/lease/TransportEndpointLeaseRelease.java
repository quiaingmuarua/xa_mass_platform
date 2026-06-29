package com.xa.mass.transport.lease;

/**
 * Release request for an endpoint lease. A stale release must not revoke a
 * newer lease for the same bucket/worker pair.
 */
public record TransportEndpointLeaseRelease(String workerId,
                                            String deliveryBucketId,
                                            String endpointDriverId,
                                            String sessionToken,
                                            String reason) {

    public TransportEndpointLeaseRelease {
        workerId = TransportEndpointLeaseClaim.requireText(workerId, "workerId");
        deliveryBucketId = TransportEndpointLeaseClaim.requireText(deliveryBucketId, "deliveryBucketId");
        endpointDriverId = TransportEndpointLeaseClaim.requireText(endpointDriverId, "endpointDriverId");
        sessionToken = TransportEndpointLeaseClaim.requireText(sessionToken, "sessionToken");
        reason = TransportEndpointLeaseClaim.normalizeNullable(reason);
    }

    boolean matches(TransportEndpointLeaseMetadata metadata) {
        return metadata != null
                && workerId.equals(metadata.workerId())
                && deliveryBucketId.equals(metadata.deliveryBucketId())
                && endpointDriverId.equals(metadata.endpointDriverId())
                && sessionToken.equals(metadata.sessionToken());
    }
}
