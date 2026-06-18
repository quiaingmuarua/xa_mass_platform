package com.xa.mass.transport.polling.runtime;

/**
 * Polling adapter-owned metadata used during embedded runtime contribution.
 */
final class PollingAdapterMetadata {

    private final String adapterId;
    private final String protocol;
    private final String transportHint;

    PollingAdapterMetadata(String adapterId, String protocol, String transportHint) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.protocol = requireText(protocol, "protocol");
        this.transportHint = requireText(transportHint, "transportHint");
    }

    String adapterId() {
        return adapterId;
    }

    String protocol() {
        return protocol;
    }

    String transportHint() {
        return transportHint;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
