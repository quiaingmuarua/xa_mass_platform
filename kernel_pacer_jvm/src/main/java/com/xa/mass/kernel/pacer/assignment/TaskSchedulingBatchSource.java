package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.ArrayList;
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

    List<DueTaskObservation> acquireAdmissionTasks(int limit) {
        long nowMillis = currentTimeMillis.getAsLong();
        List<String> taskIds = taskScore.acquireBandTaskCandidates(
                TaskScoreBand.ADMISSION_VISIBLE,
                nowMillis,
                limit
        );
        return observe(
                taskIds,
                TaskScoreBand.ADMISSION_VISIBLE,
                nowMillis,
                false
        );
    }

    List<DueTaskObservation> acquireRunningTasks(int limit) {
        long nowMillis = currentTimeMillis.getAsLong();
        return observe(
                taskScore.acquireDispatchWorkTasks(limit),
                TaskScoreBand.RUNNING_VISIBLE,
                nowMillis,
                true
        );
    }

    private List<DueTaskObservation> observe(
            List<String> taskIds,
            TaskScoreBand expectedBand,
            long nowMillis,
            boolean requireRunningSuffix
    ) {
        if (taskIds == null) {
            throw new IllegalStateException("Task source returned null ids");
        }
        if (taskIds.isEmpty()) {
            return List.of();
        }
        List<String> immutableIds = List.copyOf(taskIds);
        Map<String, TaskScoreState> states = taskScore.getScoreStates(
                immutableIds
        );
        Map<String, TaskDescriptor> descriptors =
                taskCatalog.loadTaskAllocationDescriptors(immutableIds);
        long currentSlotMillis = nowMillis / TaskScoreBandCore.SLOT_MILLIS
                * TaskScoreBandCore.SLOT_MILLIS;
        List<DueTaskObservation> observations = new ArrayList<>();
        for (String taskId : immutableIds) {
            TaskScoreState state = states.get(taskId);
            TaskDescriptor descriptor = descriptors.get(taskId);
            if (state == null
                    || descriptor == null
                    || !taskId.equals(descriptor.taskId())
                    || state.band() != expectedBand
                    || state.timeMillis() == null
                    || state.timeMillis() >= currentSlotMillis
                    || state.suffix() == null
                    || requireRunningSuffix
                    && state.suffix() != TaskScoreBandCore.MIN_SUFFIX) {
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
}
