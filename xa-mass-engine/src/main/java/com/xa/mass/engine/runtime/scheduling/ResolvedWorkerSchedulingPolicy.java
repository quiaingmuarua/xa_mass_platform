package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolved worker-side scheduling view consumed before runtime worker
 * selection.
 *
 * <p>This value defines the worker universe and static constraints. It does
 * not own live reachability, load, locks, reservations, or admission result.</p>
 */
public record ResolvedWorkerSchedulingPolicy(
        String taskId,
        String project,
        String eventCode,
        List<String> workerGroupIds,
        String adapterNodeId,
        String routingCode,
        Map<String, String> routeAttributes,
        Set<String> candidateBucketKeys,
        String targetWorkerId,
        Map<String, String> targetWorkerAttributes
) {

    public ResolvedWorkerSchedulingPolicy {
        workerGroupIds = workerGroupIds == null ? List.of() : List.copyOf(workerGroupIds);
        routeAttributes = routeAttributes == null ? Map.of() : Map.copyOf(routeAttributes);
        candidateBucketKeys = normalizeCandidateBucketKeys(candidateBucketKeys);
        targetWorkerAttributes = targetWorkerAttributes == null ? Map.of() : Map.copyOf(targetWorkerAttributes);
    }

    public static ResolvedWorkerSchedulingPolicy from(TaskDispatchIntent intent, Set<String> candidateBucketKeys) {
        TaskDispatchIntent resolvedIntent = intent == null
                ? new TaskDispatchIntent(null, null, null, List.of(), null, null, Map.of(), null, Map.of())
                : intent;
        return new ResolvedWorkerSchedulingPolicy(
                resolvedIntent.taskId(),
                resolvedIntent.project(),
                resolvedIntent.eventCode(),
                resolvedIntent.workerGroupIds(),
                resolvedIntent.adapterNodeId(),
                resolvedIntent.routingCode(),
                resolvedIntent.routeAttributes(),
                candidateBucketKeys,
                resolvedIntent.targetWorkerId(),
                resolvedIntent.targetWorkerAttributes()
        );
    }

    public boolean targetsWorker() {
        return targetWorkerId != null;
    }

    private static Set<String> normalizeCandidateBucketKeys(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY);
        }
        return Set.copyOf(values);
    }
}
