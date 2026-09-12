package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand.TaskCandidateNeed;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AssignmentPacersTest {

    @Test
    void unusedSpeculativeHoldDoesNotBlockNextOnDemandWorkForAnExecutionLease() {
        var scores = mock(WorkerScoreCore.class);
        var cache = mock(CandidateWorkerCache.class);
        var matches = matchDemands();
        var catalog = mock(WorkerResourceCatalog.class);
        var index = mock(WorkerCandidateIndex.class);
        var now = new AtomicLong(1_000L);
        var speculativeDeadline = new AtomicLong();
        when(cache.candidateWorkerCounts(List.of("precomputed"))).thenReturn(Map.of());
        when(scores.observeDueHotScoreCandidates("group-1", null, 1))
                .thenReturn(Map.of("worker", 101L));
        when(scores.acquireObservedHotScoreLeases(eq("group-1"), eq(Map.of("worker", 101L)), anyLong()))
                .thenAnswer(call -> {
                    speculativeDeadline.set(call.getArgument(2));
                    return Map.of("worker", transitioned(201L));
                });
        // The handoff is accepted, but this Worker matches none of the PRECOMPUTED needs.
        // No Cache entry or compensating release is produced; only lease expiry frees it.
        when(matches.offer(any())).thenReturn(true);
        var allocation = new TaskWorkerAllocationPolicy(scores, cache, matches, null, now::get);
        assertEquals(1, allocation.allocateCandidateWorkers(List.of(
                allocationNeed("precomputed", 1, 1))));

        now.set(1_600L);
        var selector = new TaskItemWorkerSelector(Map.of("worker.test", List.of("eligible")));
        when(index.takeWorkerIds("group-1", selector, 1)).thenReturn(List.of("worker"));
        when(scores.observeDueHotScores("group-1", List.of("worker"), null))
                .thenAnswer(call -> now.get() > speculativeDeadline.get() ? Map.of("worker", 201L) : Map.of());
        when(scores.acquireObservedHotScoreLeases("group-1", Map.of("worker", 201L), 6_600L))
                .thenReturn(Map.of("worker", transitioned(301L)));
        when(index.retainWorkerIds("group-1", selector, List.of("worker"))).thenReturn(Set.of("worker"));
        when(catalog.getWorkerDescriptors(List.of("worker"))).thenReturn(Map.of("worker",
                new WorkerResourceCatalog.WorkerDescriptor("worker", "group-1", "adapter")));

        var selected = new WorkerCandidateSelectionPolicy(scores, cache, catalog, null, index)
                .acquireOnDemandCandidates("group-1", Map.of("sms", selector), Set.of(),
                        now.get() + TaskDispatchPolicy.ITEM_CLAIM_LEASE_MILLIS);

        assertEquals("worker", selected.get("sms").workerId());
        verify(scores).acquireObservedHotScoreLeases("group-1", Map.of("worker", 201L), 6_600L);
        verify(scores, never()).releaseScoreHolds(any(), any(), anyLong());
        verify(scores, never()).releaseCompletedHotScoreHolds(any(), any(), anyLong());
    }

    @Test
    void allocationPublishesOrderedNeedsAndExactHeldScores() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchQueue matches = matchDemands();
        CandidateAllocationNeed lower = allocationNeed(
                "task-lower",
                10,
                5
        );
        CandidateAllocationNeed higher = allocationNeed(
                "task-higher",
                1,
                7
        );
        when(cache.candidateWorkerCounts(List.of(
                "task-lower", "task-higher"
        ))).thenReturn(Map.of(
                "task-lower", 4,
                "task-higher", 5
        ));
        LinkedHashMap<String, Long> observed = linkedScores(
                "worker-a", 101L,
                "worker-b", 102L,
                "worker-c", 103L
        );
        LinkedHashMap<String, Long> held = linkedScores(
                "worker-a", 201L,
                "worker-c", 203L
        );
        when(scores.observeDueHotScoreCandidates("group-1", 900L, 3))
                .thenReturn(observed);
        when(scores.acquireObservedHotScoreLeases(
                "group-1", observed, 1_500L
        )).thenReturn(Map.of(
                "worker-a", transitioned(201L),
                "worker-b", new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.STALE,
                        102L
                ),
                "worker-c", transitioned(203L)
        ));
        when(matches.offer(any())).thenReturn(true);

        int offered = allocation(scores, cache, matches, 900L)
                .allocateCandidateWorkers(
                        List.of(lower, higher)
                );

        assertEquals(1, offered);
        verify(matches).offer(argThat(demand ->
                demand.workerGroupId().equals("group-1")
                        && demand.orderedTaskNeeds().equals(List.of(
                                new TaskCandidateNeed("task-higher", 7),
                                new TaskCandidateNeed("task-lower", 5)
                        ))
                        && demand.heldWorkerLeaseScores().equals(held)
                        && demand.holdUntilMillis() == 1_500L));
        InOrder order = org.mockito.Mockito.inOrder(cache, scores, matches);
        order.verify(cache).candidateWorkerCounts(List.of(
                "task-lower", "task-higher"
        ));
        order.verify(scores).observeDueHotScoreCandidates(
                "group-1", 900L, 3
        );
        order.verify(scores).acquireObservedHotScoreLeases(
                "group-1", observed, 1_500L
        );
        order.verify(matches).offer(any());
    }

    @Test
    void fullTaskIsOmittedWithoutTakingCapacityFromLaterTask() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchQueue matches = matchDemands();
        CandidateAllocationNeed full = allocationNeed(
                "task-full",
                1,
                10
        );
        CandidateAllocationNeed next = allocationNeed(
                "task-next",
                2,
                4
        );
        when(cache.candidateWorkerCounts(List.of(
                "task-full", "task-next"
        ))).thenReturn(Map.of("task-full", 10, "task-next", 3));
        when(scores.observeDueHotScoreCandidates("group-1", null, 1))
                .thenReturn(Map.of("worker-a", 101L));
        when(scores.acquireObservedHotScoreLeases(
                "group-1", Map.of("worker-a", 101L), 1_500L
        )).thenReturn(Map.of("worker-a", transitioned(201L)));
        when(matches.offer(any())).thenReturn(true);

        assertEquals(1, allocation(scores, cache, matches)
                .allocateCandidateWorkers(
                        List.of(full, next)
                ));

        verify(matches).offer(argThat(demand ->
                demand.orderedTaskNeeds().equals(List.of(
                        new TaskCandidateNeed("task-next", 4)
                ))));
    }

    @Test
    void allocationPublishesIndependentDemandForEachWorkerGroup() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchQueue matches = matchDemands();
        when(cache.candidateWorkerCounts(List.of(
                "candidate-a", "candidate-b"
        ))).thenReturn(Map.of());
        when(scores.observeDueHotScoreCandidates("group-a", null, 1))
                .thenReturn(Map.of("worker-a", 101L));
        when(scores.observeDueHotScoreCandidates("group-b", null, 1))
                .thenReturn(Map.of("worker-b", 102L));
        when(scores.acquireObservedHotScoreLeases(
                "group-a", Map.of("worker-a", 101L), 1_500L
        )).thenReturn(Map.of("worker-a", transitioned(201L)));
        when(scores.acquireObservedHotScoreLeases(
                "group-b", Map.of("worker-b", 102L), 1_500L
        )).thenReturn(Map.of("worker-b", transitioned(202L)));
        when(matches.offer(any())).thenReturn(true);

        assertEquals(2, allocation(scores, cache, matches)
                .allocateCandidateWorkers(List.of(
                        allocationNeed("group-a", "candidate-a", 1, 1),
                        allocationNeed("group-b", "candidate-b", 1, 1)
                )));

        verify(matches).offer(argThat(demand ->
                demand.workerGroupId().equals("group-a")
                        && demand.orderedTaskNeeds().equals(List.of(
                                new TaskCandidateNeed("candidate-a", 1)
                        ))));
        verify(matches).offer(argThat(demand ->
                demand.workerGroupId().equals("group-b")
                        && demand.orderedTaskNeeds().equals(List.of(
                                new TaskCandidateNeed("candidate-b", 1)
                        ))));
    }

    @Test
    void rejectedDemandLeavesHeldWorkersToExpireNaturally() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchQueue matches = matchDemands();
        when(cache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(scores.observeDueHotScoreCandidates("group-1", null, 2))
                .thenReturn(Map.of("worker-1", 101L));
        when(scores.acquireObservedHotScoreLeases(
                "group-1", Map.of("worker-1", 101L), 1_500L
        )).thenReturn(Map.of("worker-1", transitioned(201L)));
        when(matches.offer(any())).thenReturn(false);

        assertEquals(0, allocation(scores, cache, matches)
                .allocateCandidateWorkers(
                        List.of(allocationNeed(
                                "task-1",
                                10,
                                2
                        ))
                ));

        verify(matches).offer(any());
        verify(scores, never()).releaseScoreHolds(
                any(), any(), anyLong()
        );
        verify(scores, never()).releaseCompletedHotScoreHolds(
                any(), any(), anyLong()
        );
    }

    @Test
    void nonTransitionedOrMissingHoldDoesNotPublishDemand() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerMatchQueue matches = matchDemands();
        when(cache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        LinkedHashMap<String, Long> observed = linkedScores(
                "worker-stale", 101L,
                "worker-noop", 102L,
                "worker-invalid", 103L,
                "worker-missing", 104L
        );
        when(scores.observeDueHotScoreCandidates("group-1", null, 4))
                .thenReturn(observed);
        when(scores.acquireObservedHotScoreLeases(
                "group-1", observed, 1_500L
        )).thenReturn(Map.of(
                "worker-stale", new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.STALE, 101L
                ),
                "worker-noop", new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.NOOP, 102L
                ),
                "worker-invalid", new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.INVALID, null
                )
        ));

        assertEquals(0, allocation(scores, cache, matches)
                .allocateCandidateWorkers(
                        List.of(allocationNeed(
                                "task-1",
                                10,
                                4
                        ))
                ));
        verify(matches, never()).offer(any());
    }

    @Test
    void precomputedDispatchConsumesCandidateCache() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskAssignmentDispatcher dispatcher = mock(
                TaskAssignmentDispatcher.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        TaskItem item = item("message-1", List.of());
        prepareClaimableItem(itemScores, taskRuntime, item);
        when(selection.consumeCachedCandidates(
                "group-1", "task-1", 1
        )).thenReturn(List.of(worker("worker-1", 201L)));
        when(dispatcher.dispatch(
                any(),
                any(),
                eq(6_000L)
        )).thenReturn(1);

        assertEquals(1, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                selection
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE,
                        10,
                        2
                ))
        ));

        verify(selection).consumeCachedCandidates(
                "group-1", "task-1", 1
        );
        verify(taskScores).rewriteSameBandTimeMillis(
                "task-1", TaskScoreBand.RUNNING_VISIBLE, 1_000L
        );
    }

    @Test
    void onDemandDispatchUsesTaskItemTargetsDirectly() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskAssignmentDispatcher dispatcher = mock(
                TaskAssignmentDispatcher.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        TaskItem explicit = item(
                "message-explicit", List.of("worker-target")
        );
        TaskItem anyWorker = item("message-any", List.of());
        LinkedHashMap<String, TaskItemScoreObservation> observed =
                new LinkedHashMap<>();
        observed.put(
                explicit.messageId(),
                new TaskItemScoreObservation(101L, 1)
        );
        observed.put(
                anyWorker.messageId(),
                new TaskItemScoreObservation(102L, 1)
        );
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(observed);
        when(taskRuntime.loadTaskItems(
                "task-1", List.copyOf(observed.keySet())
        )).thenReturn(Map.of(
                explicit.messageId(), explicit,
                anyWorker.messageId(), anyWorker
        ));
        var selectors = new LinkedHashMap<String, TaskItemWorkerSelector>();
        selectors.put(explicit.messageId(), explicit.workerSelector());
        selectors.put(anyWorker.messageId(), anyWorker.workerSelector());
        when(selection.acquireOnDemandCandidates(
                "group-1", selectors, Set.of(), 6_000L
        )).thenReturn(Map.of(
                explicit.messageId(), worker("worker-target", 201L),
                anyWorker.messageId(), worker("worker-any", 202L)
        ));
        when(dispatcher.dispatch(
                any(), any(), eq(6_000L)
        )).thenReturn(2);

        assertEquals(2, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                selection
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        10,
                        2
                ))
        ));

        verify(selection).acquireOnDemandCandidates(
                "group-1", selectors, Set.of(), 6_000L
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
                        999L,
                        TaskItemWorkerSelector.parse(Map.of())
                )
        ));

        assertEquals(0, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                mock(WorkerCandidateSelectionPolicy.class)
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        10,
                        2
                ))
        ));

        InOrder order = org.mockito.Mockito.inOrder(taskRuntime, itemScores);
        order.verify(taskRuntime).storeTaskItemFailedResults(
                "task-1", List.of("message-budget", "message-expired")
        );
        order.verify(itemScores).promoteItemOutcomes("task-1", outcomeTargets(List.of("message-budget", "message-expired"), 5, 1_000L));
    }

    private static TaskWorkerAllocationPolicy allocation(
            WorkerScoreCore scores,
            CandidateWorkerCache cache,
            WorkerMatchQueue matches
    ) {
        return allocation(scores, cache, matches, null);
    }

    private static TaskWorkerAllocationPolicy allocation(
            WorkerScoreCore scores,
            CandidateWorkerCache cache,
            WorkerMatchQueue matches,
            Long hotEligibilityFloorMillis
    ) {
        return new TaskWorkerAllocationPolicy(
                scores,
                cache,
                matches,
                hotEligibilityFloorMillis,
                () -> 1_000L
        );
    }

    private static WorkerMatchQueue matchDemands() {
        return mock(WorkerMatchQueue.class);
    }

    private static CandidateAllocationNeed allocationNeed(
            String taskId,
            int priority,
            int maximumCandidateWorkers
    ) {
        return allocationNeed(
                "group-1",
                taskId,
                priority,
                maximumCandidateWorkers
        );
    }

    private static CandidateAllocationNeed allocationNeed(
            String workerGroupId,
            String taskId,
            int priority,
            int maximumCandidateWorkers
    ) {
        return new CandidateAllocationNeed(
                workerGroupId,
                taskId,
                priority,
                maximumCandidateWorkers
        );
    }

    private static TaskDispatchPolicy dispatch(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher dispatcher,
            WorkerCandidateSelectionPolicy selection
    ) {
        return new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                mock(TaskIdleSettlement.class),
                selection,
                5,
                () -> 1_000L
        );
    }

    private static void prepareClaimableItem(
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskItem item
    ) {
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of(
                        item.messageId(),
                        new TaskItemScoreObservation(333_333_333L, 1)
                ));
        when(taskRuntime.loadTaskItems(
                "task-1", List.of(item.messageId())
        )).thenReturn(Map.of(item.messageId(), item));
    }

    private static ObservedTask due(
            String taskId,
            WorkerAllocationMechanism mechanism,
            TaskIdleDisposition idle,
            int priority,
            int maximumCandidateWorkers
    ) {
        return new ObservedTask(
                new TaskDescriptor(
                        taskId,
                        "group-1",
                        mechanism,
                        idle,
                        mechanism == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE ? Map.of(
                                "priority", Integer.toString(priority),
                                "maximumCandidateWorkers",
                                Integer.toString(maximumCandidateWorkers),
                                "maxRetryTimes", "1"
                        ) : Map.of("priority", Integer.toString(priority), "maxRetryTimes", "1")
                ),
                777_777_777L
        );
    }

    private static TaskItem item(
            String messageId,
            List<String> targetWorkerIds
    ) {
        return new TaskItem(
                messageId,
                "event.demo",
                0,
                Map.of(),
                0,
                null,
                TaskItemWorkerSelector.parse(targetWorkerIds.isEmpty()
                        ? Map.of() : Map.of("workerId", targetWorkerIds))
        );
    }

    private static HeldWorkerCandidate worker(
            String workerId,
            long score
    ) {
        return new HeldWorkerCandidate(
                workerId, "group-1", "adapter-1", score
        );
    }

    private static WorkerScoreTransitionResult transitioned(long score) {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score
        );
    }

    private static LinkedHashMap<String, Long> linkedScores(
            Object... pairs
    ) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], (Long) pairs[index + 1]);
        }
        return result;
    }
    private static Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> outcomeTargets(
            List<String> ids, int tag, long time
    ) {
        Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> targets = new java.util.LinkedHashMap<>();
        ids.forEach(id -> targets.put(id, new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, time)));
        return targets;
    }

}
