package com.xa.mass.transport.starter;

/**
 * Stable view of a local embedded adapter binding.
 */
public record EmbeddedTransportBindingView(
        String adapterId,
        String adapterMailboxKey,
        String transportHint
) {

    public EmbeddedTransportBindingView {
        adapterId = requireText(adapterId, "adapterId");
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        transportHint = requireText(transportHint, "transportHint");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
