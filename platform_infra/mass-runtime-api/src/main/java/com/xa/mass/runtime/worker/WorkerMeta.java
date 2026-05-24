package com.xa.mass.runtime.worker;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable worker runtime metadata for WorkerRegistry.
 */
public record WorkerMeta(
        String workerId,
        String groupId,
        String adapterNodeId,
        String adapterId,
        String transportHint,
        Map<String, String> attributes,
        String agentVersion,
        String runtimeVersion,
        long lastHeartbeatMillis,
        String diagnosticStatus
) {

    public WorkerMeta {
        workerId = requireNonBlank(workerId, "workerId");
        groupId = requireNonBlank(groupId, "groupId");
        adapterNodeId = normalizeNullable(adapterNodeId);
        adapterId = normalizeNullable(adapterId);
        transportHint = normalizeNullable(transportHint);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        agentVersion = normalizeNullable(agentVersion);
        runtimeVersion = normalizeNullable(runtimeVersion);
        diagnosticStatus = normalizeNullable(diagnosticStatus);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return Objects.requireNonNull(value, fieldName).trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
