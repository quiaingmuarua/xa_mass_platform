package com.xa.mass.worker.runtime.resource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-owned adapter node registration endpoint identity.
 */
public record AdapterNodeRecord(
        String adapterNodeId,
        String adapterType,
        String adapterVersion,
        String endpointId,
        boolean enabled,
        boolean online,
        Instant registeredAt,
        Instant lastSeenAt,
        Map<String, String> attributes
) {

    public AdapterNodeRecord {
        adapterNodeId = requireNonBlank(adapterNodeId, "adapterNodeId");
        adapterType = normalizeNullable(adapterType);
        adapterVersion = normalizeNullable(adapterVersion);
        endpointId = normalizeNullable(endpointId);
        attributes = immutableStringMap(attributes);
    }

    public AdapterNodeRecord withLifecycleTimestamps(Instant registeredAt, Instant lastSeenAt) {
        return new AdapterNodeRecord(
                adapterNodeId,
                adapterType,
                adapterVersion,
                endpointId,
                enabled,
                online,
                registeredAt,
                lastSeenAt,
                attributes
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeNullable(entry.getKey());
            String value = normalizeNullable(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }
}
