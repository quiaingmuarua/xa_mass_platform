package com.xa.mass.engine.watchdog;

import java.util.Objects;

/**
 * Stable key for a resource controlled by a polling loop.
 */
public record PollingResourceKey(String source, String resourceId) {

    public PollingResourceKey {
        source = requireText(source, "source");
        resourceId = requireText(resourceId, "resourceId");
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
