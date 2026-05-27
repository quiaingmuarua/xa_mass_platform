package com.xa.mass.runtime.worker;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime-neutral worker candidate read row used by assignment matching.
 *
 * <p>This is the candidate-source protocol, not the mutable worker entity.
 * Capability truth may still be joined from WorkerGroup by the engine-side
 * scheduling view until the runtime module owns that projection.</p>
 */
public record WorkerCandidateRow(
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
        LocalDateTime updateTime,
        boolean available
) {
    public WorkerCandidateRow {
        supportedProjects = copyList(supportedProjects);
        supportedEventCodes = copyList(supportedEventCodes);
        attributes = copyMap(attributes);
        maxConcurrentWork = Math.max(1, maxConcurrentWork);
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
}
