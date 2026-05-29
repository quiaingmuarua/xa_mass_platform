package com.xa.mass.worker.runtime.resource;

import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite worker current-state read model.
 *
 * <p>This shape still carries declaration fields together with runtime and
 * compatibility projection fields such as status, heartbeat, and
 * worker-level supported project/event hints. It is suitable for resource
 * reads and current operator views, but it is not the target declaration-store
 * persistence shape. New declaration persistence should move toward
 * {@link WorkerDeclarationRecord}; current runtime evidence should move toward
 * {@link WorkerRuntimeStateRecord}.</p>
 */
public record WorkerResourceRecord(
        String workerId,
        String statusName,
        String agentVersion,
        LocalDateTime lastHeartbeat,
        List<String> supportedProjects,
        List<String> supportedEventCodes,
        String workerGroupId,
        String adapterNodeId,
        String adapterId,
        String onlineStrategy,
        int maxConcurrentWork,
        Map<String, String> attributes,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public WorkerResourceRecord {
        workerId = normalizeNullable(workerId);
        statusName = normalizeNullable(statusName);
        agentVersion = normalizeNullable(agentVersion);
        supportedProjects = copyList(supportedProjects);
        supportedEventCodes = copyList(supportedEventCodes);
        workerGroupId = normalizeNullable(workerGroupId);
        adapterNodeId = normalizeNullable(adapterNodeId);
        adapterId = normalizeNullable(adapterId);
        onlineStrategy = normalizeNullable(onlineStrategy);
        maxConcurrentWork = Math.max(1, maxConcurrentWork);
        attributes = copyMap(attributes);
    }

    private static List<String> copyList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return List.copyOf(source);
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
