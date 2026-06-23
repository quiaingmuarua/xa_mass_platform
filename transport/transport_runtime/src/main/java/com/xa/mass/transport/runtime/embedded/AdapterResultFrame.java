package com.xa.mass.transport.runtime.embedded;

/**
 * Minimal adapter result facts before transport-owned result ingress entry construction.
 */
public record AdapterResultFrame(
        String correlationRef,
        String payload,
        String traceSeed,
        String frameId
) {
    public AdapterResultFrame {
        correlationRef = requireText(correlationRef, "correlationRef");
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        traceSeed = normalizeNullable(traceSeed);
        frameId = normalizeNullable(frameId);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
