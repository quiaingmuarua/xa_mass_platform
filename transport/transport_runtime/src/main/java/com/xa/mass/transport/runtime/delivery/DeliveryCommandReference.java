package com.xa.mass.transport.runtime.delivery;

/**
 * Handoff-owned command reference claimed by a local consumer.
 */
public record DeliveryCommandReference(String adapterMailboxKey,
                                       String commandId) {

    public DeliveryCommandReference {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        commandId = requireText(commandId, "commandId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
