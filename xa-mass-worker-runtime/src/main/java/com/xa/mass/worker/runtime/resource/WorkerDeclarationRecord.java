package com.xa.mass.worker.runtime.resource;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declaration-only worker row shape.
 *
 * <p>This is the target persisted worker declaration model. It intentionally
 * excludes heartbeat, online/offline status, dispatch gates, reservations,
 * leases, and capability history. WorkerGroup remains the capability owner;
 * worker-level supported project/event hints do not belong in this record.</p>
 */
public record WorkerDeclarationRecord(
        String workerId,
        String workerGroupId,
        String adapterNodeId,
        String adapterId,
        String onlineStrategy,
        String agentVersion,
        int maxConcurrentWork,
        Map<String, String> attributes,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public WorkerDeclarationRecord {
        workerId = normalizeNullable(workerId);
        workerGroupId = normalizeNullable(workerGroupId);
        adapterNodeId = normalizeNullable(adapterNodeId);
        adapterId = normalizeNullable(adapterId);
        onlineStrategy = normalizeNullable(onlineStrategy);
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
