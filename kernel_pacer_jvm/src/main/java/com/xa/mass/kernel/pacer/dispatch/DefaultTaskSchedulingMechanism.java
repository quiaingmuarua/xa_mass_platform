package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreScanPage;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DefaultTaskSchedulingMechanism
        implements TaskSchedulingMechanism {

    private final TaskScoreBandCore taskScores;
    private final TaskItemScoreBandCore itemScores;
    private final TaskResourceCatalog taskCatalog;

    DefaultTaskSchedulingMechanism(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog
    ) {
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.taskCatalog = Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
    }

    @Override
    public NormalTaskObservationPage observeNormalTasks(int limit) {
        if (limit < 1) {
            return new NormalTaskObservationPage(0, 0, List.of());
        }
        TaskScoreScanPage normalPage = Objects.requireNonNull(
                taskScores.acquireDispatchWorkTasks(limit),
                "Task score owner returned null scan page"
        );
        List<String> normalIds = requireIds(normalPage.taskIds());
        if (normalIds.isEmpty()) {
            return new NormalTaskObservationPage(
                    0,
                    normalPage.readAtMillis(),
                    List.of()
            );
        }
        Map<String, TaskScoreState> states = taskScores.getScoreStates(
                normalIds
        );
        Map<String, TaskDescriptor> descriptors =
                taskCatalog.loadTaskAllocationDescriptors(normalIds);
        return new NormalTaskObservationPage(
                normalIds.size(),
                normalPage.readAtMillis(),
                observe(
                        normalIds,
                        states,
                        descriptors,
                        normalPage.readAtMillis(),
                        false
                )
        );
    }

    @Override
    public List<TaskSchedulingObservation> observeInitialTasks(int limit) {
        if (limit < 1) {
            return List.of();
        }
        List<String> initialIds = requireIds(
                taskScores.acquireInitialRunningTasks(limit)
        );
        if (initialIds.isEmpty()) {
            return List.of();
        }
        Map<String, TaskScoreState> states = taskScores.getScoreStates(
                initialIds
        );
        Map<String, TaskDescriptor> descriptors =
                taskCatalog.loadTaskAllocationDescriptors(initialIds);
        return observe(
                initialIds,
                states,
                descriptors,
                0,
                true
        );
    }

    @Override
    public List<TaskSchedulingObservation> observeInitializationReady(
            List<TaskSchedulingObservation> tasks
    ) {
        List<TaskSchedulingObservation> immutable = List.copyOf(
                Objects.requireNonNull(tasks, "tasks")
        );
        if (immutable.isEmpty()) {
            return List.of();
        }
        Map<String, Boolean> due = itemScores.hasDueActiveItems(
                immutable.stream()
                        .map(TaskSchedulingObservation::taskId)
                        .toList()
        );
        return immutable.stream()
                .filter(task -> due.getOrDefault(task.taskId(), false))
                .toList();
    }

    @Override
    public int onInitializationReady(
            List<TaskSchedulingObservation> tasks
    ) {
        int initialized = 0;
        for (TaskSchedulingObservation task : List.copyOf(
                Objects.requireNonNull(tasks, "tasks")
        )) {
            TaskSchedulingReference reference = task.reference();
            if (!task.taskId().equals(reference.taskId())) {
                throw new IllegalArgumentException(
                        "Task scheduling reference identity mismatch"
                );
            }
            var result = taskScores.promoteObservedInitialTask(
                    task.taskId(),
                    reference.encodedScore()
            );
            if (result.status() == TaskScoreTransitionStatus.TRANSITIONED) {
                initialized++;
            }
        }
        return initialized;
    }

    private static List<TaskSchedulingObservation> observe(
            List<String> taskIds,
            Map<String, TaskScoreState> states,
            Map<String, TaskDescriptor> descriptors,
            long readAtMillis,
            boolean initial
    ) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        List<TaskSchedulingObservation> observations = new ArrayList<>();
        for (String taskId : taskIds) {
            TaskScoreState state = states.get(taskId);
            TaskDescriptor descriptor = descriptors.get(taskId);
            if (state == null
                    || descriptor == null
                    || !taskId.equals(descriptor.taskId())
                    || state.band() != TaskScoreBand.RUNNING_VISIBLE
                    || initial && !state.isInitial()
                    || !initial && !state.isDueNormal(readAtMillis)) {
                continue;
            }
            observations.add(new TaskSchedulingObservation(
                    taskId,
                    descriptor,
                    new TaskSchedulingReference(taskId, state.score())
            ));
        }
        return List.copyOf(observations);
    }

    private static List<String> requireIds(List<String> taskIds) {
        if (taskIds == null) {
            throw new IllegalStateException(
                    "Task score owner returned null ids"
            );
        }
        List<String> immutable = List.copyOf(taskIds);
        if (immutable.stream().anyMatch(
                taskId -> taskId == null || taskId.isBlank()
        ) || new LinkedHashSet<>(immutable).size() != immutable.size()) {
            throw new IllegalStateException(
                    "Task score owner returned invalid ids"
            );
        }
        return immutable;
    }
}
