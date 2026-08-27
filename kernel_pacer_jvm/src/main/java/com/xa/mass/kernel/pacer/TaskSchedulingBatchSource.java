package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskSchedulingBatchSource {

    private final TaskScoreBandCore taskScore;
    private final TaskResourceCatalog taskCatalog;
    private final LongSupplier currentTimeMillis;

    TaskSchedulingBatchSource(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog
    ) {
        this(taskScore, taskCatalog, System::currentTimeMillis);
    }

    TaskSchedulingBatchSource(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            LongSupplier currentTimeMillis
    ) {
        this.taskScore = Objects.requireNonNull(taskScore, "taskScore");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    TaskSchedulingBatch acquireTasks(
            int limit,
            boolean includeNormal,
            boolean includeInitial
    ) {
        if (limit <= 0 || !includeNormal && !includeInitial) {
            return TaskSchedulingBatch.empty();
        }
        long nowMillis = currentTimeMillis.getAsLong();
        List<String> normalIds = includeNormal
                ? requireIds(taskScore.acquireDispatchWorkTasks(limit))
                : List.of();
        int remaining = Math.max(0, limit - normalIds.size());
        List<String> initialIds = includeInitial && remaining > 0
                ? requireIds(taskScore.acquireInitialRunningTasks(remaining))
                : List.of();
        if (normalIds.isEmpty() && initialIds.isEmpty()) {
            return TaskSchedulingBatch.empty();
        }
        var allIds = new LinkedHashSet<String>();
        allIds.addAll(normalIds);
        allIds.addAll(initialIds);
        if (allIds.size() != normalIds.size() + initialIds.size()) {
            throw new IllegalStateException(
                    "Task scheduling source returned duplicate ids"
            );
        }
        List<String> orderedIds = List.copyOf(allIds);
        Map<String, TaskScoreState> states = taskScore.getScoreStates(
                orderedIds
        );
        Map<String, TaskDescriptor> descriptors =
                taskCatalog.loadTaskAllocationDescriptors(orderedIds);
        return new TaskSchedulingBatch(
                observe(normalIds, states, descriptors, nowMillis, false),
                observe(initialIds, states, descriptors, nowMillis, true)
        );
    }

    private List<DueTaskObservation> observe(
            List<String> taskIds,
            Map<String, TaskScoreState> states,
            Map<String, TaskDescriptor> descriptors,
            long nowMillis,
            boolean initial
    ) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        List<DueTaskObservation> observations = new ArrayList<>();
        for (String taskId : taskIds) {
            TaskScoreState state = states.get(taskId);
            TaskDescriptor descriptor = descriptors.get(taskId);
            if (state == null
                    || descriptor == null
                    || !taskId.equals(descriptor.taskId())
                    || state.band() != TaskScoreBand.RUNNING_VISIBLE
                    || initial && !state.isInitial()
                    || !initial && !state.isDueNormal(nowMillis)) {
                continue;
            }
            observations.add(new DueTaskObservation(
                    taskId,
                    state,
                    descriptor
            ));
        }
        return List.copyOf(observations);
    }

    private static List<String> requireIds(List<String> taskIds) {
        if (taskIds == null) {
            throw new IllegalStateException("Task source returned null ids");
        }
        List<String> immutable = List.copyOf(taskIds);
        if (immutable.stream().anyMatch(
                taskId -> taskId == null || taskId.isBlank()
        ) || new LinkedHashSet<>(immutable).size() != immutable.size()) {
            throw new IllegalStateException(
                    "Task source returned invalid ids"
            );
        }
        return immutable;
    }

    record TaskSchedulingBatch(
            List<DueTaskObservation> normalTasks,
            List<DueTaskObservation> initialTasks
    ) {
        TaskSchedulingBatch {
            normalTasks = List.copyOf(normalTasks);
            initialTasks = List.copyOf(initialTasks);
        }

        static TaskSchedulingBatch empty() {
            return new TaskSchedulingBatch(List.of(), List.of());
        }
    }
}
