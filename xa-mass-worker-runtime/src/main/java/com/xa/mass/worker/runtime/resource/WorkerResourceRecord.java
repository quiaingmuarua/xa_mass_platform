package com.xa.mass.worker.runtime.resource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal worker lookup read model.
 *
 * <p>This shape is declaration-oriented. Runtime status, reachability,
 * capability, topology, timestamp, and transport-owner diagnostics belong to
 * their dedicated owner ports and snapshots.</p>
 */
public record WorkerResourceRecord(
        String workerId,
        String agentVersion,
        String workerGroupId,
        String transportHint,
        int maxConcurrentWork,
        Map<String, String> attributes
) {
    public WorkerResourceRecord {
        workerId = normalizeNullable(workerId);
        agentVersion = normalizeNullable(agentVersion);
        workerGroupId = normalizeNullable(workerGroupId);
        transportHint = normalizeNullable(transportHint);
        maxConcurrentWork = Math.max(1, maxConcurrentWork);
        attributes = copyMap(attributes);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
