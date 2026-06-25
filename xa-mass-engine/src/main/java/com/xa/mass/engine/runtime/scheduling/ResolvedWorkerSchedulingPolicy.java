package com.xa.mass.engine.runtime.scheduling;

import java.util.List;
import java.util.Map;

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
        String routingCode,
        Map<String, String> routeAttributes,
        String targetWorkerId,
        Map<String, String> targetWorkerAttributes
) {

    public ResolvedWorkerSchedulingPolicy {
        workerGroupIds = workerGroupIds == null ? List.of() : List.copyOf(workerGroupIds);
        routeAttributes = routeAttributes == null ? Map.of() : Map.copyOf(routeAttributes);
        targetWorkerAttributes = targetWorkerAttributes == null ? Map.of() : Map.copyOf(targetWorkerAttributes);
    }

    public static ResolvedWorkerSchedulingPolicy from(TaskDispatchIntent intent) {
        TaskDispatchIntent resolvedIntent = intent == null
                ? new TaskDispatchIntent(null, null, null, List.of(), null, Map.of(), null, Map.of())
                : intent;
        return new ResolvedWorkerSchedulingPolicy(
                resolvedIntent.taskId(),
                resolvedIntent.project(),
                resolvedIntent.eventCode(),
                resolvedIntent.workerGroupIds(),
                resolvedIntent.routingCode(),
                resolvedIntent.routeAttributes(),
                resolvedIntent.targetWorkerId(),
                resolvedIntent.targetWorkerAttributes()
        );
    }

    public boolean targetsWorker() {
        return targetWorkerId != null;
    }

}
