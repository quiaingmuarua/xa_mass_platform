package com.xa.mass.worker.runtime.selection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trace-only projection of worker-runtime selection evidence.
 */
public record SelectedWorkerTraceEvidence(
        String eventBindingKey,
        String workerCandidateSource,
        String workerSchedulingResourceId,
        String workerSchedulingRoutingTags,
        Map<String, String> workerSchedulingAttributes,
        Boolean workerSchedulingMatchesRoutingCode,
        Double candidateScore,
        Integer workerActiveLeaseCount,
        Integer workerReservedCount,
        Integer workerDeclaredCapacity,
        Double workerEstimatedLoadRatio
) {

    public SelectedWorkerTraceEvidence {
        workerSchedulingAttributes = copyMap(workerSchedulingAttributes);
    }

    public static SelectedWorkerTraceEvidence from(SelectedWorkerHandle handle) {
        if (handle == null) {
            return empty();
        }
        return new SelectedWorkerTraceEvidence(
                handle.eventBindingKey(),
                handle.workerCandidateSource(),
                handle.workerSchedulingResourceId(),
                handle.workerSchedulingRoutingTags(),
                handle.workerSchedulingAttributes(),
                handle.workerSchedulingMatchesRoutingCode(),
                handle.candidateScore(),
                handle.workerActiveLeaseCount(),
                handle.workerReservedCount(),
                handle.workerDeclaredCapacity(),
                handle.workerEstimatedLoadRatio()
        );
    }

    public static SelectedWorkerTraceEvidence empty() {
        return new SelectedWorkerTraceEvidence(null, null, null, null, Map.of(),
                null, null, null, null, null, null);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
