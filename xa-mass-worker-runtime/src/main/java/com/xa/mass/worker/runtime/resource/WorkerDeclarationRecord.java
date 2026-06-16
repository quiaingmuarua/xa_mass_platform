package com.xa.mass.worker.runtime.resource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declaration-only worker row shape for worker-runtime owned persistence ports.
 *
 * <p>This record intentionally excludes heartbeat, online/offline state,
 * dispatch gates, reservations, leases, and worker-level supported
 * project/event capability hints.</p>
 */
public record WorkerDeclarationRecord(
        String workerId,
        String workerGroupId,
        String transportHint,
        String agentVersion,
        int maxConcurrentWork,
        Map<String, String> attributes
) {
    public WorkerDeclarationRecord {
        workerId = normalizeNullable(workerId);
        workerGroupId = normalizeNullable(workerGroupId);
        transportHint = normalizeNullable(transportHint);
        agentVersion = normalizeNullable(agentVersion);
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
