package com.xa.mass.workermatching;

import java.util.Map;
import java.util.Objects;

/** Immutable startup declaration over two literal, top-level Platform Properties keys. */
public record FixedWindowPoolDefinition(
        String poolName,
        String functionName,
        String timestampProperty,
        String countProperty,
        Map<String, WindowLimit> limitsByGroup
) {
    public record WindowLimit(long windowMillis, long maxCount) {
        public WindowLimit {
            if (windowMillis <= 0 || maxCount <= 0)
                throw new IllegalArgumentException("window length and count threshold must be positive");
        }
    }

    public FixedWindowPoolDefinition {
        requireName(poolName); requireName(functionName);
        requireName(timestampProperty); requireName(countProperty);
        if (timestampProperty.equals(countProperty))
            throw new IllegalArgumentException("window property keys must differ");
        limitsByGroup = Map.copyOf(Objects.requireNonNull(limitsByGroup));
        limitsByGroup.keySet().forEach(FixedWindowPoolDefinition::requireName);
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank name required");
    }
}
