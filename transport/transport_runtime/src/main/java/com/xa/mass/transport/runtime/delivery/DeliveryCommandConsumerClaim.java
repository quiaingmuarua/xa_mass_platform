package com.xa.mass.transport.runtime.delivery;

/**
 * Transport-internal evidence that one command consumer can drain assigned
 * delivery for a selected worker in one delivery bucket.
 */
public record DeliveryCommandConsumerClaim(String deliveryBucketId,
                                           String selectedWorkerId,
                                           String queueConsumerKey,
                                           String consumerEvidenceId,
                                           String adapterId,
                                           long leaseExpireAtEpochMillis) {

    public DeliveryCommandConsumerClaim(String deliveryBucketId,
                                        String selectedWorkerId,
                                        String queueConsumerKey,
                                        String adapterId,
                                        long leaseExpireAtEpochMillis) {
        this(deliveryBucketId,
                selectedWorkerId,
                queueConsumerKey,
                queueConsumerKey,
                adapterId,
                leaseExpireAtEpochMillis);
    }

    public DeliveryCommandConsumerClaim {
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        queueConsumerKey = requireText(queueConsumerKey, "queueConsumerKey");
        consumerEvidenceId = requireText(consumerEvidenceId, "consumerEvidenceId");
        adapterId = requireText(adapterId, "adapterId");
        leaseExpireAtEpochMillis = Math.max(0L, leaseExpireAtEpochMillis);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
