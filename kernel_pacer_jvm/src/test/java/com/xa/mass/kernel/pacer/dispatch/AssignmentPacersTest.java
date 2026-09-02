package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.DemandOfferStatus;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemMatchKey;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchEvidence;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchEvidence;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AssignmentPacersTest {

    @Test
    void allocationConsumesHeldEvidenceThenCachesAndReservesNextPool() {
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        CandidateWorkerCache candidateCache = mock(
                CandidateWorkerCache.class
        );
        WorkerMatchRuntime matches = mock(WorkerMatchRuntime.class);
        when(candidateCache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(matches.takeTaskEvidence(List.of("task-1"))).thenReturn(Map.of(
                "task-1",
                new TaskRuleMatchEvidence(
                        "task-1",
                        "group-1",
                        List.of("worker-1"),
                        2_000L
                )
        ));
        when(selection.selectHeldCandidates(
                eq("group-1"),
                any(),
                eq(Map.of("task-1", List.of("worker-1"))),
                eq(Map.of("task-1", 2_000L)),
                eq(100)
        )).thenReturn(Map.of(
                "task-1",
                List.of(worker("worker-1", 101L))
        ));
        when(selection.observeDueCandidates("group-1"))
                .thenReturn(Map.of("worker-2", 102L));
        when(matches.offerTaskDemands(any())).thenReturn(Map.of(
                "task-1", DemandOfferStatus.OFFERED
        ));
        TaskWorkerAllocationPolicy policy = new TaskWorkerAllocationPolicy(
                selection,
                candidateCache,
                matches,
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
                        101L
                )),
                2_000L
        );
        verify(matches).offerTaskDemands(argThat(demands ->
                demands.size() == 1
                        && demands.getFirst().taskId().equals("task-1")
                        && demands.getFirst().heldWorkerIds()
                                .equals(List.of("worker-2"))
                        && demands.getFirst().holdUntilMillis() == 6_000L));
        verify(selection).holdObservedCandidates(
                "group-1",
                Map.of("worker-2", 102L),
                6_000L
        );
    }

    @Test
    void allocationWithoutEvidenceOnlyPublishesBoundedDemand() {
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchRuntime matches = mock(WorkerMatchRuntime.class);
        when(cache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(matches.takeTaskEvidence(List.of("task-1")))
                .thenReturn(Map.of());
        when(selection.observeDueCandidates("group-1"))
                .thenReturn(Map.of("worker-1", 101L));
        when(matches.offerTaskDemands(any())).thenReturn(Map.of(
                "task-1", DemandOfferStatus.OFFERED
        ));

        assertEquals(0, new TaskWorkerAllocationPolicy(
                selection,
                cache,
                matches,
                () -> 1_000L
        ).allocateCandidateWorkers(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE
                )),
                new TaskWorkerAllocationConfig(5_000)
        ));
        verify(selection, never()).selectHeldCandidates(
                any(), any(), any(), any(), any(Integer.class)
        );
        verify(matches).offerTaskDemands(argThat(demands ->
                demands.getFirst().heldWorkerIds().equals(List.of("worker-1"))
                        && demands.getFirst().holdUntilMillis() == 6_000L));
        InOrder order = org.mockito.Mockito.inOrder(matches, selection);
        order.verify(matches).offerTaskDemands(any());
        order.verify(selection).holdObservedCandidates(
                "group-1",
                Map.of("worker-1", 101L),
                6_000L
        );
    }

    @Test
    void rejectedDemandDoesNotHoldTheObservedWorkerPool() {
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchRuntime matches = mock(WorkerMatchRuntime.class);
        when(cache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(matches.takeTaskEvidence(List.of("task-1")))
                .thenReturn(Map.of());
        when(selection.observeDueCandidates("group-1"))
                .thenReturn(Map.of("worker-1", 101L));
        when(matches.offerTaskDemands(any())).thenReturn(Map.of(
                "task-1", DemandOfferStatus.CAPACITY
        ));

        assertEquals(0, new TaskWorkerAllocationPolicy(
                selection,
                cache,
                matches,
                () -> 1_000L
        ).allocateCandidateWorkers(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE
                )),
                new TaskWorkerAllocationConfig(5_000)
        ));

        verify(selection, never()).holdObservedCandidates(
                any(), any(), any(Long.class)
        );
    }

    @Test
    void allocationRejectsOnDemandTasks() {
        TaskWorkerAllocationPolicy policy = new TaskWorkerAllocationPolicy(
                mock(WorkerCandidateSelectionPolicy.class),
                mock(CandidateWorkerCache.class),
                mock(WorkerMatchRuntime.class),
                () -> 1_000L
        );

        assertThrows(IllegalArgumentException.class, () ->
                policy.allocateCandidateWorkers(
                        List.of(due(
                                "task-1",
                                WorkerAllocationMechanism
                                        .ON_DEMAND_ITEM_RULE,
                                TaskIdleDisposition.PARK_WHEN_IDLE
                        )),
                        new TaskWorkerAllocationConfig(5_000)
                ));
    }

    @Test
    void onDemandDispatchUsesEvidenceThenClaimsAndDispatches() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskAssignmentDispatcher dispatcher = mock(
                TaskAssignmentDispatcher.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        WorkerMatchRuntime matches = mock(WorkerMatchRuntime.class);
        TaskItem item = item("message-1");
        ItemMatchKey key = new ItemMatchKey("task-1", "message-1");
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of(
                        "message-1",
                        new TaskItemScoreObservation(333_333_333L, 1)
                ));
        when(taskRuntime.loadTaskItems(
                "task-1", List.of("message-1")
        )).thenReturn(Map.of("message-1", item));
        when(matches.takeItemEvidence(List.of(key))).thenReturn(Map.of(
                key,
                new ItemRuleMatchEvidence(
                        key,
                        "group-1",
                        List.of("worker-1"),
                        2_000L
                )
        ));
        when(selection.selectHeldCandidates(
                eq("group-1"),
                any(),
                eq(Map.of("message-1", List.of("worker-1"))),
                eq(Map.of("message-1", 2_000L)),
                eq(100)
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

        assertEquals(1, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                selection,
                matches
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE
                )),
                new TaskDispatchConfig(100, 5_000)
        ));
        verify(taskScores).rewriteSameBandTimeMillis(
                "task-1",
                TaskScoreBand.RUNNING_VISIBLE,
                1_000L
        );
        verify(matches, never()).offerItemDemands(any());
    }

    @Test
    void missingOnDemandEvidencePublishesNextDemandWithoutDispatch() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        WorkerMatchRuntime matches = mock(WorkerMatchRuntime.class);
        TaskItem item = item("message-1");
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of(
                        "message-1",
                        new TaskItemScoreObservation(101L, 1)
                ));
        when(taskRuntime.loadTaskItems(
                "task-1", List.of("message-1")
        )).thenReturn(Map.of("message-1", item));
        when(matches.takeItemEvidence(any())).thenReturn(Map.of());
        when(selection.selectHeldCandidates(
                eq("group-1"), any(), any(), any(), eq(100)
        )).thenReturn(Map.of("message-1", List.of()));
        when(selection.observeDueCandidates("group-1"))
                .thenReturn(Map.of("worker-1", 101L));
        ItemMatchKey key = new ItemMatchKey("task-1", "message-1");
        when(matches.offerItemDemands(any())).thenReturn(Map.of(
                key, DemandOfferStatus.OFFERED
        ));

        assertEquals(0, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                selection,
                matches
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE
                )),
                new TaskDispatchConfig(100, 5_000)
        ));
        verify(matches).offerItemDemands(argThat(demands ->
                demands.size() == 1
                        && demands.getFirst().key().equals(
                                new ItemMatchKey("task-1", "message-1")
                        )
                        && demands.getFirst().heldWorkerIds()
                                .equals(List.of("worker-1"))
                        && demands.getFirst().holdUntilMillis() == 6_000L));
        verify(selection).holdObservedCandidates(
                "group-1",
                Map.of("worker-1", 101L),
                6_000L
        );
    }

    @Test
    void failedResultWriteStillPrecedesFinalScorePromotion() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        LinkedHashMap<String, TaskItemScoreObservation> observed =
                new LinkedHashMap<>();
        observed.put("message-budget", new TaskItemScoreObservation(101L, 0));
        observed.put("message-expired", new TaskItemScoreObservation(102L, 1));
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(observed);
        when(taskRuntime.loadTaskItems(
                "task-1", List.of("message-expired")
        )).thenReturn(Map.of(
                "message-expired",
                new TaskItem(
                        "message-expired",
                        "event.demo",
                        0,
                        Map.of(),
                        0,
                        999L
                )
        ));

        assertEquals(0, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                mock(WorkerCandidateSelectionPolicy.class),
                mock(WorkerMatchRuntime.class)
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE
                )),
                new TaskDispatchConfig(100, 5_000)
        ));

        InOrder order = org.mockito.Mockito.inOrder(taskRuntime, itemScores);
        order.verify(taskRuntime).storeTaskItemFailedResults(
                "task-1",
                List.of("message-budget", "message-expired")
        );
        order.verify(itemScores).promoteItemOutcomes(
                "task-1",
                List.of("message-budget", "message-expired"),
                TaskItemScoreBandCore.TaskItemScoreBand.FINAL_FAILED,
                1_000L
        );
    }

    private static TaskDispatchPolicy dispatch(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher dispatcher,
            WorkerCandidateSelectionPolicy selection,
            WorkerMatchRuntime matches
    ) {
        return new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                mock(TaskIdleSettlement.class),
                selection,
                matches,
                () -> 1_000L
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
                null
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
