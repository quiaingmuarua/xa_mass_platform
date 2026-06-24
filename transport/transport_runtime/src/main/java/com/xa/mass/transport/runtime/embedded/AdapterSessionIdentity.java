package com.xa.mass.transport.runtime.embedded;

/**
 * Adapter session evidence identity at the embedded adapter protocol boundary.
 */
public record AdapterSessionIdentity(
        String deliveryBucketId,
        String workerId
) {

    public AdapterSessionIdentity {
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        workerId = requireText(workerId, "workerId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
