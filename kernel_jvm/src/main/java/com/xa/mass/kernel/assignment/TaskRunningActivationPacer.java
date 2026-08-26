package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public final class TaskRunningActivationPacer {

    private final TaskScoreBandCore taskScore;
    private final TaskItemScoreBandCore itemScore;
    private final TaskResourceCatalog taskCatalog;
    private final CandidateWarmupSchedule warmups;
    private final LongSupplier currentTimeMillis;

    public TaskRunningActivationPacer(
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore itemScore,
            TaskResourceCatalog taskCatalog,
            CandidateWarmupSchedule warmups
    ) {
        this(
                taskScore,
                itemScore,
                taskCatalog,
                warmups,
                System::currentTimeMillis
        );
    }

    TaskRunningActivationPacer(
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore itemScore,
            TaskResourceCatalog taskCatalog,
            CandidateWarmupSchedule warmups,
            LongSupplier currentTimeMillis
    ) {
        this.taskScore = java.util.Objects.requireNonNull(
                taskScore,
                "taskScore"
        );
        this.itemScore = java.util.Objects.requireNonNull(
                itemScore,
                "itemScore"
        );
        this.taskCatalog = java.util.Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
        this.warmups = java.util.Objects.requireNonNull(warmups, "warmups");
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    public int activateRunningVisibleTasks(
            TaskRunningActivationConfig config
    ) {
        java.util.Objects.requireNonNull(config, "config");
        long nowMillis = currentTimeMillis.getAsLong();
        List<String> observedTaskIds = taskScore.acquireBandTaskCandidates(
                TaskScoreBand.ADMISSION_VISIBLE,
                nowMillis,
                config.taskBatchLimit()
        );
        if (observedTaskIds.isEmpty()) {
            return 0;
        }
        Map<String, TaskScoreState> observedStates =
                taskScore.getScoreStates(observedTaskIds);
        Map<String, TaskDescriptor> loaded =
                taskCatalog.loadTaskAllocationDescriptors(observedTaskIds);
        LinkedHashMap<String, TaskDescriptor> descriptors =
                new LinkedHashMap<>();
        observedTaskIds.forEach(taskId -> {
            TaskDescriptor descriptor = loaded.get(taskId);
            if (descriptor != null) {
                descriptors.put(taskId, descriptor);
            }
        });
        Map<String, Boolean> due = itemScore.hasDueActiveItems(
                List.copyOf(descriptors.keySet())
        );
        List<String> taskAllowed = descriptors.keySet().stream()
                .filter(taskId -> due.getOrDefault(taskId, false))
                .toList();
        int availableSlots = Math.max(
                0,
                config.runningTaskSoftLimit()
                        - taskScore.countRunningCapacityTasks()
        );
        List<String> systemAllowed = taskAllowed.stream()
                .limit(availableSlots)
                .toList();
        List<String> activated = new ArrayList<>();
        for (String taskId : systemAllowed) {
            var result = taskScore.rewriteScore(
                    taskId,
                    TaskScoreBand.ADMISSION_VISIBLE,
                    nowMillis,
                    TaskScoreBand.RUNNING_VISIBLE,
                    TaskScoreBandCore.MIN_SUFFIX
            );
            if (result.status() == TaskScoreTransitionStatus.TRANSITIONED) {
                activated.add(taskId);
            }
        }
        for (String taskId : observedTaskIds) {
            if (activated.contains(taskId)) {
                continue;
            }
            TaskScoreState state = observedStates.get(taskId);
            if (state == null
                    || state.band() != TaskScoreBand.ADMISSION_VISIBLE
                    || state.suffix() == null) {
                continue;
            }
            int priorityBucket = state.suffix() / 10;
            taskScore.rewriteSameBandTimeMillis(
                    taskId,
                    TaskScoreBand.ADMISSION_VISIBLE,
                    Math.addExact(
                            Math.addExact(
                                    nowMillis,
                                    TaskScoreBandCore.SLOT_MILLIS
                            ),
                            Math.multiplyExact(
                                    priorityBucket,
                                    config.priorityRecheckStepMillis()
                            )
                    )
            );
        }
        List<String> precomputed = activated.stream()
                .filter(taskId -> descriptors.get(taskId)
                        .workerAllocationMechanism()
                        == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE)
                .toList();
        if (!precomputed.isEmpty()) {
            warmups.scheduleCandidateWarmups(precomputed, nowMillis);
        }
        return activated.size();
    }
}
