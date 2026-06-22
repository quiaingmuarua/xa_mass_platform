package com.xa.mass.transport.runtime.delivery;

/**
 * Store-owned claim reference used for handoff ack/requeue hygiene.
 */
public record DispatchHandoffReference(String adapterMailboxKey,
                                       String deliveryId) {

    public DispatchHandoffReference {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        deliveryId = requireText(deliveryId, "deliveryId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
