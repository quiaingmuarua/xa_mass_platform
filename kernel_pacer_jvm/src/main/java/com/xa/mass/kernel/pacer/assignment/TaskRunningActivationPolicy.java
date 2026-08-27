package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskRunningActivationPolicy {

    private final TaskScoreBandCore taskScore;
    private final TaskItemScoreBandCore itemScore;
    private final LongSupplier currentTimeMillis;

    TaskRunningActivationPolicy(
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore itemScore
    ) {
        this(taskScore, itemScore, System::currentTimeMillis);
    }

    TaskRunningActivationPolicy(
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore itemScore,
            LongSupplier currentTimeMillis
    ) {
        this.taskScore = Objects.requireNonNull(taskScore, "taskScore");
        this.itemScore = Objects.requireNonNull(itemScore, "itemScore");
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int activateRunningVisibleTasks(
            List<DueTaskObservation> tasks,
            TaskRunningActivationConfig config
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        if (tasks.isEmpty()) {
            return 0;
        }
        long nowMillis = currentTimeMillis.getAsLong();
        List<String> taskIds = tasks.stream()
                .map(DueTaskObservation::taskId)
                .toList();
        Map<String, Boolean> due = itemScore.hasDueActiveItems(taskIds);
        List<DueTaskObservation> taskAllowed = tasks.stream()
                .filter(task -> due.getOrDefault(task.taskId(), false))
                .toList();
        int availableSlots = Math.max(
                0,
                config.runningTaskSoftLimit()
                        - taskScore.countRunningCapacityTasks()
        );
        List<DueTaskObservation> systemAllowed = taskAllowed.stream()
                .limit(availableSlots)
                .toList();
        List<String> activated = new ArrayList<>();
        for (DueTaskObservation task : systemAllowed) {
            var result = taskScore.rewriteScore(
                    task.taskId(),
                    TaskScoreBand.ADMISSION_VISIBLE,
                    nowMillis,
                    TaskScoreBand.RUNNING_VISIBLE,
                    TaskScoreBandCore.MIN_SUFFIX
            );
            if (result.status() == TaskScoreTransitionStatus.TRANSITIONED) {
                activated.add(task.taskId());
            }
        }
        for (DueTaskObservation task : tasks) {
            if (activated.contains(task.taskId())) {
                continue;
            }
            TaskScoreState state = task.scoreState();
            int priorityBucket = state.suffix() / 10;
            taskScore.rewriteSameBandTimeMillis(
                    task.taskId(),
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
        return activated.size();
    }
}
