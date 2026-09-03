package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import java.util.List;
import java.util.Objects;

final class TaskIdleSettlement {

    private final TaskScoreBandCore taskScores;
    private final TaskItemScoreBandCore itemScores;

    TaskIdleSettlement(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores
    ) {
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
    }

    void settle(
            ObservedTask task,
            TaskIdleDisposition disposition,
            long observedAtMillis
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(disposition, "disposition");
        boolean active = itemScores.hasActiveItems(
                List.of(task.taskId())
        ).getOrDefault(task.taskId(), false);
        if (active) {
            taskScores.rewriteSameBandTimeMillis(
                    task.taskId(),
                    TaskScoreBand.RUNNING_VISIBLE,
                    observedAtMillis
            );
            return;
        }
        if (disposition == TaskIdleDisposition.CLOSE_WHEN_IDLE) {
            taskScores.closeObservedScore(
                    task.taskId(),
                    task.observedTaskScore(),
                    TaskScoreBandCore.TERMINAL_SCORE_MAX
            );
            return;
        }
        var parked = taskScores.parkObservedIdleTask(
                task.taskId(),
                task.observedTaskScore()
        );
        if (parked.status() != TaskScoreTransitionStatus.TRANSITIONED) {
            return;
        }
        boolean appeared = itemScores.hasActiveItems(
                List.of(task.taskId())
        ).getOrDefault(task.taskId(), false);
        if (appeared) {
            taskScores.tryReleaseIdlePark(task.taskId());
        }
    }
}
