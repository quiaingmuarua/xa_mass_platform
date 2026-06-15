package com.xa.mass.transport.runtime.delivery;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Transport-local assigned-delivery command queue address.
 */
final class AssignedDeliveryCommandQueueKey {

    private static final int MAX_BUCKET_ID_BYTES = 512;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private AssignedDeliveryCommandQueueKey() {
    }

    static String queueKeyFor(String deliveryBucketId) {
        String normalized = normalize(deliveryBucketId);
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BUCKET_ID_BYTES) {
            throw new IllegalArgumentException("deliveryBucketId is too long for transport queue addressing");
        }
        return "bucket:" + ENCODER.encodeToString(bytes);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("deliveryBucketId must not be blank");
        }
        return value.trim();
    }
}
