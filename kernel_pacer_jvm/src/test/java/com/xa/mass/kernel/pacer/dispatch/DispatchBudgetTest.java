package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DispatchBudgetTest {
    @Test void mainPreparesOnceAndRefillCanRunWhileDispatchHasNotRun() {
        var scores=mock(TaskScoreBandCore.class);
        var catalog=mock(TaskResourceCatalog.class);
        var index=mock(WorkerCandidateIndex.class);
        var hold=mock(WorkerCandidateIndex.InitialHold.class);
        var dispatch=mock(TaskDispatchPolicy.class);
        var query=mock(WorkerCandidateIndex.TaskQuery.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task",123L,"initial",100L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of("initial",100L));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task",descriptor()));
        when(index.prepareTaskQueries(Map.of("task","group"))).thenReturn(Map.of("task",query));
        var initialization=mock(TaskInitializationPolicy.class);
        var scheduler=new DispatchMainScheduler(scores,catalog,initialization,dispatch,index,hold,null,AssignmentDispatchConfig.defaults(),null);
        var executor=new ManualExecutor(); var run=scheduler.new SchedulerRun(executor,() -> 0);
        run.step();
        assertEquals(3,executor.pending.size());
        executor.pending.remove(1).run(); // Refill only; initialization and dispatch are still queued.
        verify(index).refill(Map.of("task",query),1000,hold);
        verifyNoInteractions(initialization,dispatch,query);
        executor.pending.removeLast().run();
        verify(dispatch).dispatchTasks(List.of(new ObservedTask(descriptor(),123L)),Map.of("task",query));
        verify(index,times(1)).prepareTaskQueries(Map.of("task","group"));
        verifyNoMoreInteractions(index);
    }

    private static TaskRuntime.TaskDescriptor descriptor() {
        return new TaskRuntime.TaskDescriptor("task", "group", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "10", "maxRetryTimes", "1"));
    }

    @Test void aSlowRoundRemainsSingleFlightAndNextIntervalStartsAfterCompletion() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var dispatch = mock(TaskDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task", 123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor()));
        var scheduler = new DispatchMainScheduler(scores, catalog, mock(TaskInitializationPolicy.class),
                dispatch, mock(WorkerCandidateIndex.class), mock(WorkerCandidateIndex.InitialHold.class), null, AssignmentDispatchConfig.defaults(), null);
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
        verify(dispatch, times(2)).dispatchTasks(anyList(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test void oneTaskChecksAtMostOneHundredItemsPerRoundAndLeavesTheRestForLater() {
        var scores = mock(TaskItemScoreBandCore.class);
        var runtime = mock(TaskRuntime.class);
        var pending = new LinkedHashMap<String, TaskItemScoreBandCore.TaskItemScoreObservation>();
        for (int i = 0; i < 101; i++) pending.put("item-" + i, new TaskItemScoreBandCore.TaskItemScoreObservation(123L, 0));
        var observedSizes = new ArrayList<Integer>();
        when(scores.acquireItemScoreCandidates(eq("task"), anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(1);
            assertEquals(100, limit);
            var page = new LinkedHashMap<String, TaskItemScoreBandCore.TaskItemScoreObservation>();
            pending.entrySet().stream().limit(limit).forEach(e -> page.put(e.getKey(), e.getValue()));
            observedSizes.add(page.size());
            return page;
        });
        doAnswer(call -> { ((List<String>) call.getArgument(1)).forEach(pending::remove); return null; })
                .when(runtime).storeTaskItemFailedResults(eq("task"), anyList());
        var policy = new TaskDispatchPolicy(mock(TaskScoreBandCore.class), scores, runtime,
                mock(TaskAssignmentDispatcher.class), mock(TaskIdleSettlement.class),
                mock(WorkerCandidateSelectionPolicy.class), 5, () -> 1_000L);
        var input = List.of(new ObservedTask(descriptor(), 123L));
        policy.dispatchTasks(input, Map.of());
        assertEquals(Set.of("item-100"), pending.keySet());
        policy.dispatchTasks(input, Map.of());
        assertTrue(pending.isEmpty());
        assertEquals(List.of(100, 1), observedSizes);
    }

    @Test void serviceabilityUsesTheNextExistingTaskObservationAfterAnEmptyPage() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var serviceability = mock(WorkerServiceabilityDispatchPolicy.class);
        var clock = new AtomicLong();
        // Task pacing can hide the Group in the current 100 ms score slot.
        // Every 1-second Serviceability deadline deliberately falls in that gap.
        when(scores.acquireSchedulingTasks(100)).thenAnswer(call ->
                clock.get() / 1_000_000L % 100 == 0 ? Map.of() : Map.of("task", 123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(catalog.loadTaskAllocationDescriptors(List.of("task")))
                .thenReturn(Map.of("task", descriptor()));
        var config = WorkerServiceabilityDispatchConfig.defaults(100L);
        var scheduler = new DispatchMainScheduler(scores, catalog, mock(TaskInitializationPolicy.class),
                mock(TaskDispatchPolicy.class), mock(WorkerCandidateIndex.class), mock(WorkerCandidateIndex.InitialHold.class), serviceability, AssignmentDispatchConfig.defaults(), config);
        var executor = new ManualExecutor();
        var run = scheduler.new SchedulerRun(executor, clock::get);
        for (int millis = 0; millis <= 3_000; millis += 50) {
            clock.set(TimeUnit.MILLISECONDS.toNanos(millis));
            run.step();
            while (!executor.pending.isEmpty()) executor.pending.removeFirst().run();
            run.step(); // Apply completions; waiting for input must not create a source poll.
        }
        verify(serviceability, times(3)).dispatchProbes(List.of("group"), config);
        verify(scores, times(61)).acquireSchedulingTasks(100);
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
