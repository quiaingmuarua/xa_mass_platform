package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionResult;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssignmentPacersTest {

    @Test
    void allocationPublishesCandidatesFromRunningTaskBatch() {
        WorkerCandidateAcquirer acquirer = mock(WorkerCandidateAcquirer.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        TaskDescriptor descriptor = descriptor(
                "task-1",
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE
        );
        when(cache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        CandidateWorkerEntry candidate = new CandidateWorkerEntry(
                "worker-1",
                "group-1",
                "adapter-1",
                111L
        );
        when(acquirer.acquireHotPoolCandidates(
                eq("group-1"),
                any(),
                eq(6_000L)
        )).thenReturn(Map.of("task-1", List.of(candidate)));
        TaskWorkerAllocationPolicy policy = new TaskWorkerAllocationPolicy(
                acquirer,
                cache,
                () -> 1_000L
        );

        assertEquals(1, policy.allocateCandidateWorkers(
                List.of(new DueTaskObservation(
                        "task-1",
                        runningState("task-1", 900L),
                        descriptor
                )),
                new TaskWorkerAllocationConfig(5_000)
        ));
        verify(cache).appendCandidateWorkers(
                "task-1",
                List.of(candidate),
                6_000L
        );
    }

    @Test
    void initializationPromotesOnlyInitialTasksWithDueItems() {
        TaskScoreBandCore taskScore = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScore = mock(TaskItemScoreBandCore.class);
        TaskScoreState first = initialState("task-1", 10_000);
        TaskScoreState second = initialState("task-2", 9_900);
        TaskDescriptor firstDescriptor = descriptor(
                "task-1",
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE
        );
        TaskDescriptor secondDescriptor = descriptor(
                "task-2",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE
        );
        when(itemScore.hasDueActiveItems(List.of("task-1", "task-2")))
                .thenReturn(Map.of("task-1", true, "task-2", false));
        when(taskScore.promoteObservedInitialTask(
                "task-1",
                first.score()
        )).thenReturn(new TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                1L
        ));
        TaskInitializationPolicy policy = new TaskInitializationPolicy(
                taskScore,
                itemScore
        );

        assertEquals(1, policy.initializeTasks(
                List.of(
                        new DueTaskObservation(
                                "task-1",
                                first,
                                firstDescriptor
                        ),
                        new DueTaskObservation(
                                "task-2",
                                second,
                                secondDescriptor
                        )
                )
        ));
        verify(taskScore).promoteObservedInitialTask(
                "task-1",
                first.score()
        );
        verify(taskScore, never()).promoteObservedInitialTask(
                "task-2",
                second.score()
        );
    }

    @Test
    void dispatchParksEmptyReusableTaskAndReleasesConcurrentAppend() {
        TaskScoreBandCore taskScore = mock(TaskScoreBandCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        TaskItemScoreBandCore itemScore = mock(TaskItemScoreBandCore.class);
        TaskItemDispatcher dispatcher = mock(TaskItemDispatcher.class);
        TaskScoreState state = runningState("task-1", 900L);
        TaskDescriptor taskDescriptor = descriptor(
                "task-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE
        );
        when(dispatcher.observeClaimableTaskItems(
                "task-1",
                100,
                1_000L
        )).thenReturn(List.of());
        when(itemScore.hasActiveItems(List.of("task-1")))
                .thenReturn(Map.of("task-1", false))
                .thenReturn(Map.of("task-1", true));
        when(taskScore.parkObservedIdleTask("task-1", state.score()))
                .thenReturn(new TaskScoreTransitionResult(
                        TaskScoreTransitionStatus.TRANSITIONED,
                        1L
                ));
        TaskDispatchPolicy policy = new TaskDispatchPolicy(
                taskScore,
                commands,
                itemScore,
                dispatcher,
                () -> 1_000L
        );

        assertEquals(0, policy.dispatchTasks(
                List.of(new DueTaskObservation(
                        "task-1",
                        state,
                        taskDescriptor
                )),
                new TaskDispatchConfig(100, 5_000)
        ));
        verify(taskScore).tryReleaseIdlePark("task-1");
        verify(commands, never()).appendWorkerCommands(any(), any());
    }

    private static TaskScoreState runningState(String taskId, long time) {
        return new TaskScoreState(
                taskId,
                1L,
                TaskScoreBand.RUNNING_VISIBLE,
                time,
                0
        );
    }

    private static TaskScoreState initialState(String taskId, long time) {
        return new TaskScoreState(
                taskId,
                2L,
                TaskScoreBand.RUNNING_VISIBLE,
                time,
                0
        );
    }

    private static TaskDescriptor descriptor(
            String taskId,
            WorkerAllocationMechanism mechanism,
            TaskIdleDisposition idle
    ) {
        return new TaskDescriptor(
                taskId,
                "group-1",
                mechanism,
                idle,
                mechanism == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                        ? Map.of()
                        : null,
                Map.of(
                        "priority", "10",
                        "maximumCandidateWorkers", "2",
                        "maxRetryTimes", "1"
                )
        );
    }
}
