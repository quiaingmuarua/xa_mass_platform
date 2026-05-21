package com.xa.mass.engine.worker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-owned relation that an adapter node hosts a worker group.
 */
public record NodeGroupBindingRecord(
        String adapterNodeId,
        String groupId,
        String pluginVersion,
        String deploymentVersion,
        boolean enabled,
        boolean draining,
        Instant registeredAt,
        Instant updatedAt,
        Map<String, String> attributes
) {

    public NodeGroupBindingRecord {
        adapterNodeId = requireNonBlank(adapterNodeId, "adapterNodeId");
        groupId = requireNonBlank(groupId, "groupId");
        pluginVersion = normalizeNullable(pluginVersion);
        deploymentVersion = normalizeNullable(deploymentVersion);
        attributes = immutableStringMap(attributes);
    }

    NodeGroupBindingRecord withLifecycleTimestamps(Instant registeredAt, Instant updatedAt) {
        return new NodeGroupBindingRecord(
                adapterNodeId,
                groupId,
                pluginVersion,
                deploymentVersion,
                enabled,
                draining,
                registeredAt,
                updatedAt,
                attributes
        );
    }

    NodeGroupBindingRecord withEnabled(boolean enabled, Instant updatedAt) {
        return new NodeGroupBindingRecord(
                adapterNodeId,
                groupId,
                pluginVersion,
                deploymentVersion,
                enabled,
                draining,
                registeredAt,
                updatedAt,
                attributes
        );
    }

    NodeGroupBindingRecord withDraining(boolean draining, Instant updatedAt) {
        return new NodeGroupBindingRecord(
                adapterNodeId,
                groupId,
                pluginVersion,
                deploymentVersion,
                enabled,
                draining,
                registeredAt,
                updatedAt,
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
