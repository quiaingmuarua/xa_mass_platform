package com.xa.mass.transport.runtime.embedded;

import java.util.Map;
import java.util.Objects;

/**
 * Minimal embedded adapter startup intent.
 */
public record EmbeddedAdapterRuntimeSpec(
        String type,
        String adapterId,
        String dispatchQueueKey,
        String resultQueueKey,
        Map<String, String> options) {

    public EmbeddedAdapterRuntimeSpec {
        type = requireText(type, "type").toLowerCase(java.util.Locale.ROOT);
        adapterId = requireText(adapterId, "adapterId").toLowerCase(java.util.Locale.ROOT);
        dispatchQueueKey = requireText(dispatchQueueKey, "dispatchQueueKey");
        resultQueueKey = requireText(resultQueueKey, "resultQueueKey");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public String option(String key) {
        if (key == null) {
            return null;
        }
        return options.get(key);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
