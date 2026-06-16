package com.xa.mass.transport.runtime.delivery;

/**
 * Handoff-owned command reference claimed by a local consumer.
 */
public record DeliveryCommandReference(String deliveryQueueKey,
                                       String commandId) {

    public DeliveryCommandReference {
        deliveryQueueKey = requireText(deliveryQueueKey, "deliveryQueueKey");
        commandId = requireText(commandId, "commandId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
