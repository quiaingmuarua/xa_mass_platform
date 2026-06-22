package com.xa.mass.transport.runtime;

import java.util.Objects;

/**
 * Host-assigned adapter bootstrap facts.
 */
public record AdapterBootstrapAssignment(
        TransportAdapterDescriptor descriptor,
        String adapterMailboxKey
) {

    public AdapterBootstrapAssignment {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
    }

    public String adapterId() {
        return requireDescriptor().getAdapterId();
    }

    public String transportHint() {
        return requireDescriptor().getTransportHint();
    }

    private TransportAdapterDescriptor requireDescriptor() {
        if (descriptor == null) {
            throw new IllegalStateException("adapter descriptor is required for this bootstrap capability");
        }
        return descriptor;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
