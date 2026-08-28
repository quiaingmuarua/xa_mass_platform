package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.TaskSchedulingMechanism.NormalTaskObservationPage;
import com.xa.mass.kernel.pacer.dispatch.TaskSchedulingMechanism.TaskSchedulingObservation;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DispatchTaskBatchFanout {

    private final TaskSchedulingMechanism mechanism;

    DispatchTaskBatchFanout(TaskSchedulingMechanism mechanism) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
    }

    Map<DispatchLaneId, List<DueTaskObservation>> acquireFor(
            Set<DispatchLaneId> eligibleLanes,
            int limit
    ) {
        Objects.requireNonNull(eligibleLanes, "eligibleLanes");
        if (eligibleLanes.isEmpty() || limit < 1) {
            return Map.of();
        }
        NormalTaskObservationPage normal = mechanism.observeNormalTasks(limit);
        int remaining = Math.max(0, limit - normal.sourceCount());
        boolean includeInitial = remaining > 0 && eligibleLanes.contains(
                DispatchLaneId.TASK_INITIALIZATION
        );
        List<TaskSchedulingObservation> initial = includeInitial
                ? mechanism.observeInitialTasks(remaining)
                : List.of();
        Map<DispatchLaneId, List<DueTaskObservation>> routed =
                new EnumMap<>(DispatchLaneId.class);
        for (DispatchLaneId laneId : eligibleLanes) {
            List<DueTaskObservation> laneBatch = laneId.consumesInitialTasks()
                    ? observations(initial)
                    : observations(normal.tasks());
            if (!laneBatch.isEmpty()) {
                routed.put(laneId, laneBatch);
            }
        }
        return Map.copyOf(routed);
    }

    private static List<DueTaskObservation> observations(
            List<TaskSchedulingObservation> tasks
    ) {
        return tasks.stream()
                .map(task -> new DueTaskObservation(
                        task.taskId(),
                        task.reference(),
                        task.descriptor()
                ))
                .toList();
    }
}
