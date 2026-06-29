package com.xa.mass.transport.lease;

/**
 * Refresh request for an existing endpoint lease.
 */
public record TransportEndpointLeaseHeartbeat(String workerId,
                                              String deliveryBucketId,
                                              String endpointDriverId,
                                              String sessionToken,
                                              String reason) {

    public TransportEndpointLeaseHeartbeat {
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
