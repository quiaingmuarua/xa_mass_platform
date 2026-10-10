package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DispatchBudgetTest {
    @Test void schedulerRunsDoNotShareFullCandidateHintsAndEmptyRootsRetireThem() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var refill = mock(WorkerEligibilityRefillPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of());
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of("task", 123L));
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("task", descriptor()));
        var observed = new ArrayList<CandidateRecycleHints>();
        when(refill.refill(anyList(), anyList(), any(CandidateRecycleHints.class))).thenAnswer(call -> {
            CandidateRecycleHints hints = call.getArgument(2); observed.add(hints);
            assertEquals(0, hints.pending()); hints.offer("group", Map.of("w", 1L), List.of("w")); return 0;
        });
        var scheduler = new DispatchMainScheduler(scores, catalog, mock(TaskInitializationPolicy.class),
                mock(TaskDispatchPolicy.class), refill, null, AssignmentDispatchConfig.defaults(), null);
        var nanos = new AtomicLong(); var firstExecutor = new ManualExecutor();
        var first = scheduler.new SchedulerRun(firstExecutor, nanos::get);
        first.step(); while (!firstExecutor.pending.isEmpty()) firstExecutor.pending.removeFirst().run();
        first.step();
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of());
        nanos.set(1_000_000_000L); first.step();
        assertEquals(0, observed.getFirst().pending());
        observed.getFirst().offer("group", Map.of("old", 2L), List.of("old"));
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of("task", 123L));
        var secondExecutor = new ManualExecutor(); scheduler.new SchedulerRun(secondExecutor, nanos::get).step();
        while (!secondExecutor.pending.isEmpty()) secondExecutor.pending.removeFirst().run();
        assertEquals(2, observed.size()); assertNotSame(observed.get(0), observed.get(1));
    }
    @Test void mainSharesCompleteDescriptorsAndRefillCanRunBeforeDispatch() {
        var scores=mock(TaskScoreBandCore.class);
        var catalog=mock(TaskResourceCatalog.class);
        var index=mock(WorkerMatching.class);
        var hold=mock(WorkerEligibilityRefillPolicy.class);
        var dispatch=mock(TaskDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task",123L,"initial",100L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of("initial",100L));
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of("task",123L));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task",descriptor()));

        var initialization=mock(TaskInitializationPolicy.class);
        var scheduler=new DispatchMainScheduler(scores,catalog,initialization,dispatch,hold,null,AssignmentDispatchConfig.defaults(),null);
        var executor=new ManualExecutor(); var run=scheduler.new SchedulerRun(executor,() -> 0);
        run.step();
        assertEquals(3,executor.pending.size());
        executor.pending.remove(1).run(); // Refill only; initialization and dispatch are still queued.
        verify(hold).refill(eq(List.of("group")),eq(List.of(descriptor())), org.mockito.ArgumentMatchers.any(CandidateRecycleHints.class));
        verifyNoInteractions(initialization,dispatch);
        executor.pending.removeLast().run();
        verify(dispatch).dispatchTasks(List.of(new ObservedTask(descriptor(),123L)));
        verify(catalog,times(1)).loadTaskAllocationDescriptors(List.of("task"));
        verifyNoMoreInteractions(index);
    }

    @Test void failedRefillDoesNotPreventIndependentDispatch() {
        var scores=mock(TaskScoreBandCore.class);
        var catalog=mock(TaskResourceCatalog.class);
        var refill=mock(WorkerEligibilityRefillPolicy.class);
        var dispatch=mock(TaskDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task",123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task",descriptor()));
        when(refill.refill(anyList(),anyList(), org.mockito.ArgumentMatchers.any(CandidateRecycleHints.class))).thenThrow(new IllegalStateException("Matching unavailable"));
        var scheduler=new DispatchMainScheduler(scores,catalog,mock(TaskInitializationPolicy.class),
                dispatch,refill,null,AssignmentDispatchConfig.defaults(),null);
        var executor=new ManualExecutor();
        scheduler.new SchedulerRun(executor,()->0).step();
        while(!executor.pending.isEmpty())executor.pending.removeFirst().run();
        verify(dispatch).dispatchTasks(List.of(new ObservedTask(descriptor(),123L)));
    }

    @Test void refillKeepsRunningTasksThatAreNotDueInThisRound() {
        var scores=mock(TaskScoreBandCore.class);
        var catalog=mock(TaskResourceCatalog.class);
        var refill=mock(WorkerEligibilityRefillPolicy.class);
        var dispatch=mock(TaskDispatchPolicy.class);
        // Dispatch just rewrote the only Task into the current slot, so no Task is due.
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of());
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of("task",123L));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task",descriptor()));
        var scheduler=new DispatchMainScheduler(scores,catalog,mock(TaskInitializationPolicy.class),
                dispatch,refill,null,AssignmentDispatchConfig.defaults(),null);
        var executor=new ManualExecutor();
        scheduler.new SchedulerRun(executor,()->0).step();
        while(!executor.pending.isEmpty())executor.pending.removeFirst().run();
        verify(refill).refill(eq(List.of("group")),eq(List.of(descriptor())), org.mockito.ArgumentMatchers.any(CandidateRecycleHints.class));
        verifyNoInteractions(dispatch);
    }

    @Test void failedSupplyRootsDeferOnlyRefill() {
        var scores=mock(TaskScoreBandCore.class);
        var catalog=mock(TaskResourceCatalog.class);
        var refill=mock(WorkerEligibilityRefillPolicy.class);
        var dispatch=mock(TaskDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task",123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeNormalRunningTasksAscending(100)).thenThrow(new IllegalStateException("Redis unavailable"));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task",descriptor()));
        var scheduler=new DispatchMainScheduler(scores,catalog,mock(TaskInitializationPolicy.class),
                dispatch,refill,null,AssignmentDispatchConfig.defaults(),null);
        var executor=new ManualExecutor();
        scheduler.new SchedulerRun(executor,()->0).step();
        while(!executor.pending.isEmpty())executor.pending.removeFirst().run();
        verify(dispatch).dispatchTasks(List.of(new ObservedTask(descriptor(),123L)));
        verifyNoInteractions(refill);
    }

    private static TaskRuntime.TaskDescriptor descriptor() {
        return new TaskRuntime.TaskDescriptor("task", "test-project", "group", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "10", "maxRetryTimes", "1"), java.util.List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(java.util.Map.of()), 100)), null, java.util.Map.of());
    }

    @Test void aSlowRoundRemainsSingleFlightAndNextIntervalStartsAfterCompletion() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var dispatch = mock(TaskDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task", 123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of("task", 123L));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor()));
        var scheduler = new DispatchMainScheduler(scores, catalog, mock(TaskInitializationPolicy.class),
                dispatch, mock(WorkerEligibilityRefillPolicy.class), null, AssignmentDispatchConfig.defaults(), null);
        var clock = new AtomicLong(1_000_000_000L);
        var executor = new ManualExecutor();
        var run = scheduler.new SchedulerRun(executor, clock::get);
        run.step();
        assertEquals(2, executor.pending.size());
        clock.addAndGet(500_000_000L); // Producer still occupies its one slot through ten nominal dispatch intervals.
        run.step();
        assertEquals(2, executor.pending.size());
        verifyNoInteractions(dispatch);
        while (!executor.pending.isEmpty()) executor.pending.removeFirst().run();
        run.step(); // Observe completion at 1.5 s; next eligible at 1.55 s, not at 1.05 s.
        assertTrue(executor.pending.isEmpty());
        clock.addAndGet(49_999_999L);
        run.step();
        assertTrue(executor.pending.isEmpty());
        clock.incrementAndGet();
        run.step();
        assertEquals(2, executor.pending.size());
        while (!executor.pending.isEmpty()) executor.pending.removeFirst().run();
        verify(dispatch, times(2)).dispatchTasks(anyList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 100, 101, 333, 1000})
    void oneTaskChecksAtMostConfiguredItemsPerRoundAndLeavesTheRestForLater(int batchLimit) {
        var scores = mock(TaskItemScoreBandCore.class);
        var runtime = mock(TaskRuntime.class);
        var pending = new LinkedHashMap<String, TaskItemScoreBandCore.TaskItemScoreObservation>();
        for (int i = 0; i < batchLimit + 1; i++) pending.put("item-" + i, new TaskItemScoreBandCore.TaskItemScoreObservation(123L, 0));
        var observedSizes = new ArrayList<Integer>();
        when(scores.acquireItemScoreCandidates(eq("task"), anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(1);
            assertEquals(batchLimit, limit);
            var page = new LinkedHashMap<String, TaskItemScoreBandCore.TaskItemScoreObservation>();
            pending.entrySet().stream().limit(limit).forEach(e -> page.put(e.getKey(), e.getValue()));
            observedSizes.add(page.size());
            return page;
        });
        doAnswer(call -> { ((List<String>) call.getArgument(1)).forEach(pending::remove); return null; })
                .when(runtime).storeTaskItemFailedResults(eq("task"), anyList());
        var policy = new TaskDispatchPolicy(mock(TaskScoreBandCore.class), scores, runtime,
                mock(TaskAssignmentDispatcher.class), mock(TaskIdleSettlement.class),
                mock(WorkerCandidateSelectionPolicy.class), batchLimit, 5, () -> 1_000L);
        var input = List.of(new ObservedTask(descriptor(), 123L));
        policy.dispatchTasks(input);
        assertEquals(Set.of("item-" + batchLimit), pending.keySet());
        policy.dispatchTasks(input);
        assertTrue(pending.isEmpty());
        assertEquals(List.of(batchLimit, 1), observedSizes);
    }

    @Test void serviceabilityRootsFollowTheirOwnIntervalIndependentOfDueTaskPacing() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var serviceability = mock(WorkerServiceabilityDispatchPolicy.class);
        // Task pacing hides the parked Task from every due page; RUNNING roots still expose its Group.
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of());
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeRunningTasksAscending(100)).thenReturn(Map.of("task", 123L));
        when(catalog.loadTaskAllocationDescriptors(List.of("task")))
                .thenReturn(Map.of("task", descriptor()));
        var config = WorkerServiceabilityDispatchConfig.recoveryOnly();

        runThreeSeconds(scores, catalog, serviceability, config);

        verify(serviceability, times(4)).dispatchProbes(List.of("group"), config);
        verify(scores, times(4)).observeRunningTasksAscending(100);
    }

    @Test void emptyServiceabilityRootsWaitTheOrdinaryIntervalWithoutRepolling() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var serviceability = mock(WorkerServiceabilityDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task", 123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeRunningTasksAscending(100)).thenReturn(Map.of());
        when(catalog.loadTaskAllocationDescriptors(List.of("task")))
                .thenReturn(Map.of("task", descriptor()));

        runThreeSeconds(scores, catalog, serviceability, WorkerServiceabilityDispatchConfig.recoveryOnly());

        verify(serviceability, never()).dispatchProbes(anyList(), any());
        verify(scores, times(4)).observeRunningTasksAscending(100);
    }

    private static void runThreeSeconds(
            TaskScoreBandCore scores,
            TaskResourceCatalog catalog,
            WorkerServiceabilityDispatchPolicy serviceability,
            WorkerServiceabilityDispatchConfig config
    ) {
        var clock = new AtomicLong();
        var scheduler = new DispatchMainScheduler(scores, catalog, mock(TaskInitializationPolicy.class),
                mock(TaskDispatchPolicy.class), mock(WorkerEligibilityRefillPolicy.class), serviceability, AssignmentDispatchConfig.defaults(), config);
        var executor = new ManualExecutor();
        var run = scheduler.new SchedulerRun(executor, clock::get);
        for (int millis = 0; millis <= 3_000; millis += 50) {
            clock.set(TimeUnit.MILLISECONDS.toNanos(millis));
            run.step();
            while (!executor.pending.isEmpty()) executor.pending.removeFirst().run();
            run.step(); // Apply completions before the next tick.
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final LinkedList<Runnable> pending = new LinkedList<>();
        public void execute(Runnable command) { pending.add(command); }
        public void shutdown() {}
        public List<Runnable> shutdownNow() { return List.copyOf(pending); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }
}
