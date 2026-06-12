package com.xa.mass.worker.runtime.candidate;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        String agentVersion,
        String workerGroupId,
        String adapterNodeId,
        String adapterId,
        String onlineStrategy,
        Map<String, String> attributes
) {
    public WorkerCandidateRow {
        attributes = copyMap(attributes);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
