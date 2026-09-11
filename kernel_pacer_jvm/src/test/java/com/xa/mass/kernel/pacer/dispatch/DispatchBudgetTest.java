package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DispatchBudgetTest {
    private static TaskRuntime.TaskDescriptor descriptor() {
        return new TaskRuntime.TaskDescriptor("task", "group",
                TaskRuntime.WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "10", "maximumCandidateWorkers", "100", "maxRetryTimes", "1"));
    }

    @Test void aSlowRoundRemainsSingleFlightAndNextIntervalStartsAfterCompletion() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var dispatch = mock(TaskDispatchPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task", 123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor()));
        var scheduler = new DispatchMainScheduler(scores, catalog, mock(TaskInitializationPolicy.class),
                mock(TaskWorkerAllocationPolicy.class), dispatch, null, AssignmentDispatchConfig.defaults(), null);
        var clock = new AtomicLong(1_000_000_000L);
        var executor = new ManualExecutor();
        var run = scheduler.new SchedulerRun(executor, clock::get);
        run.step();
        assertEquals(1, executor.pending.size());
        clock.addAndGet(500_000_000L); // Producer still occupies its one slot through ten nominal dispatch intervals.
        run.step();
        assertEquals(1, executor.pending.size());
        verifyNoInteractions(dispatch);
        executor.pending.removeFirst().run();
        run.step(); // Observe completion at 1.5 s; next eligible at 1.55 s, not at 1.05 s.
        assertTrue(executor.pending.isEmpty());
        clock.addAndGet(49_999_999L);
        run.step();
        assertTrue(executor.pending.isEmpty());
        clock.incrementAndGet();
        run.step();
        assertEquals(1, executor.pending.size());
        executor.pending.removeFirst().run();
        verify(dispatch, times(2)).dispatchTasks(anyList());
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
        policy.dispatchTasks(input);
        assertEquals(Set.of("item-100"), pending.keySet());
        policy.dispatchTasks(input);
        assertTrue(pending.isEmpty());
        assertEquals(List.of(100, 1), observedSizes);
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
