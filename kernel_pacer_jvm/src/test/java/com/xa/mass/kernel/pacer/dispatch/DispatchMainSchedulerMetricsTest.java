package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.MetricsSink;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DispatchMainSchedulerMetricsTest {

    @Test
    void recordsSuccessfulAndFailedProducerRoundsWithOutputsAndDurations() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var init = mock(TaskInitializationPolicy.class);
        var dispatch = mock(TaskDispatchPolicy.class);
        var refill = mock(WorkerEligibilityRefillPolicy.class);

        var descriptor = descriptor();
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task-1", 123L, "init-1", 100L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of("init-1", 100L));
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of("task-1", 123L));
        when(catalog.loadTaskAllocationDescriptors(List.of("task-1"))).thenReturn(Map.of("task-1", descriptor));

        var nanos = new AtomicLong(1_000L);
        when(dispatch.dispatchTasks(anyList())).thenAnswer(inv -> {
            nanos.addAndGet(500L);
            return 7;
        });
        when(refill.refill(anyList(), anyList(), any(CandidateRecycleHints.class))).thenAnswer(inv -> {
            nanos.addAndGet(200L);
            throw new IllegalStateException("refill unavailable");
        });

        record Round(String producer, int output, long durationNanos, boolean failed) {}
        var rounds = new ArrayList<Round>();
        MetricsSink sink = new MetricsSink() {
            @Override
            public void onDispatchRound(String producer, int outputCount, long durationNanos, boolean failed) {
                rounds.add(new Round(producer, outputCount, durationNanos, failed));
            }
        };

        var scheduler = new DispatchMainScheduler(
                scores, catalog, init, dispatch, refill, null,
                AssignmentDispatchConfig.defaults(), null
        );
        scheduler.setMetricsSink(sink);
        var executor = new ManualExecutor();
        var run = scheduler.new SchedulerRun(executor, nanos::get);
        run.step();
        while (!executor.pending.isEmpty()) {
            executor.pending.removeFirst().run();
        }
        run.step();

        assertEquals(3, rounds.size());
        var initRound = rounds.stream().filter(r -> r.producer().equals("TASK_INITIALIZATION")).findFirst().orElseThrow();
        var refillRound = rounds.stream().filter(r -> r.producer().equals("ELIGIBILITY_REFILL")).findFirst().orElseThrow();
        var dispatchRound = rounds.stream().filter(r -> r.producer().equals("TASK_DISPATCH")).findFirst().orElseThrow();

        assertEquals(0, initRound.output());
        assertFalse(initRound.failed());

        assertEquals(0, refillRound.output());
        assertEquals(200L, refillRound.durationNanos());
        assertTrue(refillRound.failed());

        assertEquals(7, dispatchRound.output());
        assertEquals(500L, dispatchRound.durationNanos());
        assertFalse(dispatchRound.failed());
    }

    @Test
    void ignoresSinkExceptionsWithoutBreakingSchedulingProgress() {
        var scores = mock(TaskScoreBandCore.class);
        var catalog = mock(TaskResourceCatalog.class);
        var dispatch = mock(TaskDispatchPolicy.class);
        var refill = mock(WorkerEligibilityRefillPolicy.class);
        when(scores.acquireSchedulingTasks(100)).thenReturn(Map.of("task-1", 123L));
        when(scores.filterInitialTaskScores(anyMap())).thenReturn(Map.of());
        when(scores.observeNormalRunningTasksAscending(100)).thenReturn(Map.of());
        when(catalog.loadTaskAllocationDescriptors(List.of("task-1"))).thenReturn(Map.of("task-1", descriptor()));
        when(dispatch.dispatchTasks(anyList())).thenReturn(3);

        MetricsSink throwingSink = new MetricsSink() {
            @Override
            public void onDispatchRound(String producer, int outputCount, long durationNanos, boolean failed) {
                throw new RuntimeException("sink boom");
            }
        };

        var scheduler = new DispatchMainScheduler(
                scores, catalog, mock(TaskInitializationPolicy.class), dispatch, refill, null,
                AssignmentDispatchConfig.defaults(), null
        );
        scheduler.setMetricsSink(throwingSink);
        var executor = new ManualExecutor();
        var nanos = new AtomicLong();
        var run = scheduler.new SchedulerRun(executor, nanos::get);
        run.step();
        assertEquals(1, executor.pending.size());
        executor.pending.removeFirst().run();
        // Completion must still have been enqueued and drained cleanly.
        run.step();
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        run.step();
        assertEquals(1, executor.pending.size());
    }

    private static TaskRuntime.TaskDescriptor descriptor() {
        return new TaskRuntime.TaskDescriptor(
                "task-1",
                "group",
                TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "10", "maxRetryTimes", "1"),
                List.of(),
                null,
                Map.of()
        );
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable command) { pending.addLast(command); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
