package com.xa.mass.kernel.pacer.dispatch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionResult;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskIdleSettlementTest {

    @Test
    void activeItemsKeepOrdinaryRunningPacing() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        when(itemScores.hasActiveItems(List.of("task-1")))
                .thenReturn(Map.of("task-1", true));

        new TaskIdleSettlement(taskScores, itemScores).settle(
                dueTask(),
                TaskIdleDisposition.PARK_WHEN_IDLE,
                1_000L
        );

        verify(taskScores).rewriteSameBandTimeMillis(
                "task-1",
                TaskScoreBand.RUNNING_VISIBLE,
                1_000L
        );
        verify(taskScores, never()).parkObservedIdleTask(
                "task-1",
                777_777_777L
        );
    }

    @Test
    void closeUsesTheObservedTaskScoreUnchanged() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        when(itemScores.hasActiveItems(List.of("task-1")))
                .thenReturn(Map.of("task-1", false));

        new TaskIdleSettlement(taskScores, itemScores).settle(
                dueTask(),
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                1_000L
        );

        verify(taskScores).closeObservedScore(
                "task-1",
                777_777_777L,
                TaskScoreBandCore.TERMINAL_SCORE_MAX
        );
    }

    @Test
    void itemAppearingAfterParkReleasesTheIdlePark() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        when(itemScores.hasActiveItems(List.of("task-1")))
                .thenReturn(Map.of("task-1", false))
                .thenReturn(Map.of("task-1", true));
        when(taskScores.parkObservedIdleTask(
                "task-1",
                777_777_777L
        )).thenReturn(new TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                888_888_888L
        ));

        new TaskIdleSettlement(taskScores, itemScores).settle(
                dueTask(),
                TaskIdleDisposition.PARK_WHEN_IDLE,
                1_000L
        );

        verify(taskScores).parkObservedIdleTask(
                "task-1",
                777_777_777L
        );
        verify(taskScores).tryReleaseIdlePark("task-1");
    }

    private static DueTaskObservation dueTask() {
        return new DueTaskObservation(
                "task-1",
                777_777_777L,
                new TaskDescriptor(
                        "task-1",
                        "group-1",
                        WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        null,
                        Map.of(
                                "priority", "0",
                                "maximumCandidateWorkers", "1",
                                "maxRetryTimes", "1"
                        )
                )
        );
    }
}
