package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionResult;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

class AssignmentPacersTest {

    @Test
    void allocationCachesOnlyOwnerReobservedActiveLeaseScores() {
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        CandidateWorkerCache candidateCache = mock(
                CandidateWorkerCache.class
        );
        AcquiredWorkerCandidate candidate = worker("worker-1", 101L);
        when(candidateCache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(selection.acquireSharedHotCandidates(
                eq("group-1"),
                any(),
                eq(6_000L)
        )).thenReturn(Map.of("task-1", List.of(candidate)));
        when(workerScores.observeActiveHotScoreLeases(
                "group-1",
                List.of("worker-1"),
                6_000L
        )).thenReturn(Map.of("worker-1", 987_654_321L));
        TaskWorkerAllocationPolicy policy = new TaskWorkerAllocationPolicy(
                selection,
                workerScores,
                candidateCache,
                () -> 1_000L
        );

        assertEquals(1, policy.allocateCandidateWorkers(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE
                )),
                new TaskWorkerAllocationConfig(5_000)
        ));
        verify(candidateCache).appendCandidateWorkers(
                "task-1",
                List.of(new CandidateWorkerEntry(
                        "worker-1",
                        "group-1",
                        987_654_321L
                )),
                6_000L
        );
    }

    @Test
    void allocationDoesNotPublishWhenLeaseReobservationIsEmpty() {
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        CandidateWorkerCache candidateCache = mock(
                CandidateWorkerCache.class
        );
        when(candidateCache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(selection.acquireSharedHotCandidates(
                eq("group-1"), any(), eq(6_000L)
        )).thenReturn(Map.of(
                "task-1", List.of(worker("worker-1", 101L))
        ));
        when(workerScores.observeActiveHotScoreLeases(
                "group-1", List.of("worker-1"), 6_000L
        )).thenReturn(Map.of());

        assertEquals(0, new TaskWorkerAllocationPolicy(
                selection,
                workerScores,
                candidateCache,
                () -> 1_000L
        ).allocateCandidateWorkers(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE
                )),
                new TaskWorkerAllocationConfig(5_000)
        ));
    }

    @Test
    void allocationRejectsTasksOutsideTheMainSchedulerInputSet() {
        TaskWorkerAllocationPolicy policy = new TaskWorkerAllocationPolicy(
                mock(WorkerCandidateSelectionPolicy.class),
                mock(WorkerScoreCore.class),
                mock(CandidateWorkerCache.class),
                () -> 1_000L
        );

        assertThrows(IllegalArgumentException.class, () ->
                policy.allocateCandidateWorkers(
                        List.of(due(
                                "task-1",
                                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                                TaskIdleDisposition.PARK_WHEN_IDLE
                        )),
                        new TaskWorkerAllocationConfig(5_000)
                )
        );
    }

    @Test
    void dispatchUsesRawItemScoreAndAlwaysPacesAfterAttempt() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskAssignmentDispatcher dispatcher = mock(
                TaskAssignmentDispatcher.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        TaskIdleSettlement idle = mock(TaskIdleSettlement.class);
        TaskItem item = item("message-1");
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of(
                        "message-1",
                        new TaskItemScoreObservation(333_333_333L, 1)
                ));
        when(taskRuntime.loadTaskItems(
                "task-1", List.of("message-1")
        )).thenReturn(Map.of("message-1", item));
        when(selection.acquireOnDemandCandidates(
                eq("group-1"),
                any(),
                eq(6_000L)
        )).thenReturn(Map.of(
                "message-1", List.of(worker("worker-1", 101L))
        ));
        when(dispatcher.dispatch(
                any(),
                eq(Map.of("message-1", item)),
                eq(Map.of("message-1", 333_333_333L)),
                any(),
                eq(6_000L)
        )).thenReturn(1);
        DueTaskObservation task = due(
                "task-1",
                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE
        );

        assertEquals(1, new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                idle,
                selection,
                () -> 1_000L
        ).dispatchTasks(List.of(task), new TaskDispatchConfig(100, 5_000)));
        verify(taskScores).rewriteSameBandTimeMillis(
                "task-1",
                TaskScoreBand.RUNNING_VISIBLE,
                1_000L
        );
    }

    @Test
    void noClaimableItemUsesIdleSettlementWithObservedTaskScore() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskIdleSettlement idle = new TaskIdleSettlement(
                taskScores,
                itemScores
        );
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of());
        when(itemScores.hasActiveItems(List.of("task-1")))
                .thenReturn(Map.of("task-1", false));
        when(taskScores.parkObservedIdleTask("task-1", 777_777_777L))
                .thenReturn(new TaskScoreTransitionResult(
                        TaskScoreTransitionStatus.STALE,
                        null
                ));

        assertEquals(0, new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                idle,
                mock(WorkerCandidateSelectionPolicy.class),
                () -> 1_000L
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE
                )),
                new TaskDispatchConfig(100, 5_000)
        ));
        verify(taskScores).parkObservedIdleTask(
                "task-1",
                777_777_777L
        );
    }

    @Test
    void dispatchStoresBudgetAndTtlFailuresBeforeFinalScorePromotion() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskIdleSettlement idle = mock(TaskIdleSettlement.class);
        var observed = new LinkedHashMap<
                String,
                TaskItemScoreObservation
                >();
        observed.put(
                "message-budget",
                new TaskItemScoreObservation(101L, 0)
        );
        observed.put(
                "message-expired",
                new TaskItemScoreObservation(102L, 1)
        );
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(observed);
        TaskItem expired = new TaskItem(
                "message-expired",
                "event.demo",
                0,
                Map.of(),
                0,
                999L,
                Map.of()
        );
        when(taskRuntime.loadTaskItems(
                "task-1",
                List.of("message-expired")
        )).thenReturn(Map.of("message-expired", expired));

        assertEquals(0, new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                idle,
                mock(WorkerCandidateSelectionPolicy.class),
                () -> 1_000L
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE
                )),
                new TaskDispatchConfig(100, 5_000)
        ));

        InOrder terminalOrder = org.mockito.Mockito.inOrder(
                taskRuntime,
                itemScores
        );
        terminalOrder.verify(taskRuntime).storeTaskItemFailedResults(
                "task-1",
                List.of("message-budget", "message-expired")
        );
        terminalOrder.verify(itemScores).promoteItemOutcomes(
                "task-1",
                List.of("message-budget", "message-expired"),
                TaskItemScoreBandCore.TaskItemScoreBand.FINAL_FAILED,
                1_000L
        );
    }

    @Test
    void failedResultWritePreventsFinalScorePromotion() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of(
                        "message-budget",
                        new TaskItemScoreObservation(101L, 0)
                ));
        org.mockito.Mockito.doThrow(
                new IllegalStateException("result owner unavailable")
        ).when(taskRuntime).storeTaskItemFailedResults(
                "task-1",
                List.of("message-budget")
        );
        TaskDispatchPolicy policy = new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                mock(TaskIdleSettlement.class),
                mock(WorkerCandidateSelectionPolicy.class),
                () -> 1_000L
        );

        assertThrows(IllegalStateException.class, () ->
                policy.dispatchTasks(
                        List.of(due(
                                "task-1",
                                WorkerAllocationMechanism
                                        .ON_DEMAND_ITEM_RULE,
                                TaskIdleDisposition.PARK_WHEN_IDLE
                        )),
                        new TaskDispatchConfig(100, 5_000)
                )
        );
        verify(itemScores, never()).promoteItemOutcomes(
                eq("task-1"),
                eq(List.of("message-budget")),
                eq(TaskItemScoreBandCore.TaskItemScoreBand.FINAL_FAILED),
                eq(1_000L)
        );
    }

    private static DueTaskObservation due(
            String taskId,
            WorkerAllocationMechanism mechanism,
            TaskIdleDisposition idle
    ) {
        return new DueTaskObservation(
                taskId,
                777_777_777L,
                descriptor(taskId, mechanism, idle)
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

    private static TaskItem item(String messageId) {
        return new TaskItem(
                messageId,
                "event.demo",
                0,
                Map.of(),
                0,
                null,
                Map.of()
        );
    }

    private static AcquiredWorkerCandidate worker(
            String workerId,
            long score
    ) {
        return new AcquiredWorkerCandidate(
                workerId,
                "group-1",
                "adapter-1",
                score
        );
    }
}
