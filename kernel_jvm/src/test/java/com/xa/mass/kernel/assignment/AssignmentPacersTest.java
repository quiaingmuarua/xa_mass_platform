package com.xa.mass.kernel.assignment;

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
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssignmentPacersTest {

    @Test
    void allocationPublishesCandidatesAndRequeuesIncompleteTask() {
        CandidateWarmupSchedule warmups = mock(CandidateWarmupSchedule.class);
        TaskScoreBandCore taskScore = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        WorkerCandidateAcquirer acquirer = mock(WorkerCandidateAcquirer.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        when(warmups.consumeDueCandidateWarmups(1_000L, 100))
                .thenReturn(List.of("task-1"));
        when(taskScore.getScoreStates(List.of("task-1"))).thenReturn(Map.of(
                "task-1",
                runningState("task-1", 900L)
        ));
        TaskDescriptor descriptor = descriptor(
                "task-1",
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE
        );
        when(catalog.loadTaskAllocationDescriptors(List.of("task-1")))
                .thenReturn(Map.of("task-1", descriptor));
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
        TaskWorkerAllocationPacer pacer = new TaskWorkerAllocationPacer(
                warmups,
                taskScore,
                catalog,
                acquirer,
                cache,
                () -> 1_000L
        );

        assertEquals(1, pacer.allocateCandidateWorkers(
                new TaskWorkerAllocationConfig(100, 5_000)
        ));
        verify(cache).appendCandidateWorkers(
                "task-1",
                List.of(candidate),
                6_000L
        );
        verify(warmups).scheduleCandidateWarmups(
                List.of("task-1"),
                1_000L
        );
    }

    @Test
    void activationRequiresDueItemAndReschedulesOtherAdmissionTasks() {
        TaskScoreBandCore taskScore = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScore = mock(TaskItemScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        CandidateWarmupSchedule warmups = mock(CandidateWarmupSchedule.class);
        when(taskScore.acquireBandTaskCandidates(
                TaskScoreBand.ADMISSION_VISIBLE,
                1_000L,
                100
        )).thenReturn(List.of("task-1", "task-2"));
        TaskScoreState first = admissionState("task-1", 10);
        TaskScoreState second = admissionState("task-2", 20);
        when(taskScore.getScoreStates(List.of("task-1", "task-2")))
                .thenReturn(Map.of("task-1", first, "task-2", second));
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
        when(catalog.loadTaskAllocationDescriptors(
                List.of("task-1", "task-2")
        )).thenReturn(Map.of(
                "task-1", firstDescriptor,
                "task-2", secondDescriptor
        ));
        when(itemScore.hasDueActiveItems(List.of("task-1", "task-2")))
                .thenReturn(Map.of("task-1", true, "task-2", false));
        when(taskScore.countRunningCapacityTasks()).thenReturn(0);
        when(taskScore.rewriteScore(
                "task-1",
                TaskScoreBand.ADMISSION_VISIBLE,
                1_000L,
                TaskScoreBand.RUNNING_VISIBLE,
                0
        )).thenReturn(new TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                1L
        ));
        TaskRunningActivationPacer pacer = new TaskRunningActivationPacer(
                taskScore,
                itemScore,
                catalog,
                warmups,
                () -> 1_000L
        );

        assertEquals(1, pacer.activateRunningVisibleTasks(
                new TaskRunningActivationConfig(100, 1_000, 100)
        ));
        verify(taskScore).rewriteSameBandTimeMillis(
                "task-2",
                TaskScoreBand.ADMISSION_VISIBLE,
                3_100L
        );
        verify(warmups).scheduleCandidateWarmups(
                List.of("task-1"),
                1_000L
        );
    }

    @Test
    void dispatchParksEmptyReusableTaskAndReleasesConcurrentAppend() {
        TaskScoreBandCore taskScore = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        TaskItemScoreBandCore itemScore = mock(TaskItemScoreBandCore.class);
        TaskItemDispatcher dispatcher = mock(TaskItemDispatcher.class);
        when(taskScore.acquireDispatchWorkTasks(100))
                .thenReturn(List.of("task-1"));
        TaskScoreState state = runningState("task-1", 900L);
        when(taskScore.getScoreStates(List.of("task-1")))
                .thenReturn(Map.of("task-1", state));
        when(catalog.loadTaskAllocationDescriptors(List.of("task-1")))
                .thenReturn(Map.of("task-1", descriptor(
                        "task-1",
                        WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE
                )));
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
        TaskDispatchPacer pacer = new TaskDispatchPacer(
                taskScore,
                catalog,
                commands,
                itemScore,
                dispatcher,
                () -> 1_000L
        );

        assertEquals(0, pacer.dispatchTasks(
                new TaskDispatchConfig(100, 100, 5_000)
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

    private static TaskScoreState admissionState(String taskId, int priority) {
        return new TaskScoreState(
                taskId,
                2L,
                TaskScoreBand.ADMISSION_VISIBLE,
                900L,
                priority
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
