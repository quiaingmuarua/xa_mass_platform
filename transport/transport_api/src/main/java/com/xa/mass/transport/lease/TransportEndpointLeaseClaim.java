package com.xa.mass.transport.lease;

/**
 * Transport-owned endpoint lease write.
 *
 * <p>The stable lookup key is {@code deliveryBucketId + workerId}. Endpoint
 * driver, endpoint address, session handle, and lease id are transport-local
 * evidence for the current connection/session.</p>
 */
public record TransportEndpointLeaseClaim(String workerId,
                                          String deliveryBucketId,
                                          String endpointDriverId,
                                          String endpointAddress,
                                          String sessionHandle,
                                          String endpointLeaseId,
                                          String reason) {

    public TransportEndpointLeaseClaim(String workerId,
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

    public TransportEndpointLeaseClaim {
        workerId = requireText(workerId, "workerId");
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        endpointDriverId = requireText(endpointDriverId, "endpointDriverId");
        endpointAddress = requireText(endpointAddress, "endpointAddress");
        sessionHandle = requireText(sessionHandle, "sessionHandle");
        endpointLeaseId = requireText(endpointLeaseId, "endpointLeaseId");
        reason = normalizeNullable(reason);
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

    static String requireText(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
