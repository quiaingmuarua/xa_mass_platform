package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TaskInitializationPolicy {

    private final TaskScoreBandCore taskScore;
    private final TaskItemScoreBandCore itemScore;

    TaskInitializationPolicy(
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore itemScore
    ) {
        this.taskScore = Objects.requireNonNull(taskScore, "taskScore");
        this.itemScore = Objects.requireNonNull(itemScore, "itemScore");
    }

    int initializeTasks(List<DueTaskObservation> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return 0;
        }
        List<String> taskIds = tasks.stream()
                .map(DueTaskObservation::taskId)
                .toList();
        Map<String, Boolean> due = itemScore.hasDueActiveItems(taskIds);
        int initialized = 0;
        for (DueTaskObservation task : tasks) {
            if (!due.getOrDefault(task.taskId(), false)) {
                continue;
            }
            var result = taskScore.promoteObservedInitialTask(
                    task.taskId(),
                    task.scoreState().score()
            );
            if (result.status() == TaskScoreTransitionStatus.TRANSITIONED) {
                initialized++;
            }
        }
        return initialized;
    }
}
