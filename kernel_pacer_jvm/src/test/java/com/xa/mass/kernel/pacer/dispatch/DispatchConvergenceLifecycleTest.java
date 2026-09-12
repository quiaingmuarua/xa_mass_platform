package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DispatchConvergenceLifecycleTest {

    @Test
    void oneSourceObservationPlansAllFixedProducerInputs() throws Exception {
        Fixture fixture = fixture(
                oneShotAssignment(),
                enabledServiceability()
        );
        stubProjectedBatch(fixture);
        CountDownLatch rounds = new CountDownLatch(3);
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        AtomicReference<List<String>> dispatchedTasks = new AtomicReference<>();
        AtomicReference<List<String>> serviceabilityGroups =
                new AtomicReference<>();
        doAnswer(ignored -> {
            complete(rounds, allVirtual);
            return null;
        }).when(fixture.initialization).initialize(any());
        doAnswer(invocation -> {
            List<ObservedTask> tasks = invocation.getArgument(0);
            dispatchedTasks.set(taskIds(tasks));
            return complete(rounds, allVirtual);
        }).when(fixture.dispatch).dispatchTasks(any());
        doAnswer(invocation -> {
            List<String> groups = invocation.getArgument(0);
            serviceabilityGroups.set(groups);
            return complete(rounds, allVirtual);
        }).when(fixture.serviceability).dispatchProbes(
                any(), any()
        );

        fixture.runtime.start();

        assertTrue(rounds.await(2, TimeUnit.SECONDS));
        assertTrue(allVirtual.get());
        verify(fixture.taskScores).acquireSchedulingTasks(100);
        verify(fixture.taskScores).filterInitialTaskScores(any());
        verify(fixture.initialization).initialize(Map.of(
                "task-initial", 100L
        ));
        verify(fixture.taskCatalog).loadTaskAllocationDescriptors(List.of(
                "task-first",
                "task-second",
                "task-repeat-group",
                "task-invalid"
        ));
        assertEquals(
                List.of(
                        "task-first",
                        "task-second",
                        "task-repeat-group"
                ),
                dispatchedTasks.get()
        );
        assertEquals(
                List.of("group-1", "group-2"),
                serviceabilityGroups.get()
        );
        assertTrue(fixture.runtime.isRunning());
        assertThrows(IllegalStateException.class, () ->
                fixture.runtime.start()
        );

        fixture.runtime.stop(2_000);
        fixture.runtime.stop(2_000);
        assertEquals("STOPPED", fixture.runtime.state());
    }

    @Test
    void sourceFailureDefersEveryEligibleProducer() throws Exception {
        Fixture fixture = fixture(
                oneShotAssignment(),
                enabledServiceability()
        );
        CountDownLatch sourceAttempt = new CountDownLatch(1);
        when(fixture.taskScores.acquireSchedulingTasks(100)).thenAnswer(
                ignored -> {
                    sourceAttempt.countDown();
                    throw new IllegalStateException("source unavailable");
                }
        );

        fixture.runtime.start();

        assertTrue(sourceAttempt.await(2, TimeUnit.SECONDS));
        verify(fixture.initialization, never()).initialize(any());
        verify(fixture.dispatch, never()).dispatchTasks(any());
        verify(fixture.serviceability, never()).dispatchProbes(
                any(), any()
        );
        assertTrue(fixture.runtime.isRunning());
        fixture.runtime.stop(2_000);
    }


    @Test
    void persistentlyDueNormalTaskIsRediscoveredAcrossCadences()
            throws Exception {
        Fixture fixture = fixture(fastAssignment(), null);
        stubNormalBatch(fixture);
        CountDownLatch dispatchRounds = new CountDownLatch(3);
        doAnswer(ignored -> {
            dispatchRounds.countDown();
            return 1;
        }).when(fixture.dispatch).dispatchTasks(any());

        fixture.runtime.start();

        assertTrue(dispatchRounds.await(2, TimeUnit.SECONDS));
        verify(fixture.taskScores, atLeast(3)).acquireSchedulingTasks(100);
        fixture.runtime.stop(2_000);
    }


    @Test
    void blockedDispatchDoesNotStopServiceabilityAndShutdownInterruptsItsOneRun() throws Exception {
        Fixture fixture = fixture(fastAssignment(), enabledServiceability());
        stubNormalBatch(fixture);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var probes = new CountDownLatch(3);
        var dispatches = new AtomicInteger();
        doAnswer(call -> {
            dispatches.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stop) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return 0;
        }).when(fixture.dispatch).dispatchTasks(any());
        doAnswer(call -> { probes.countDown(); return 0; })
                .when(fixture.serviceability).dispatchProbes(any(), any());
        fixture.runtime.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(probes.await(2, TimeUnit.SECONDS));
            assertEquals(1, dispatches.get());
        } finally { fixture.runtime.stop(2000); }
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals("STOPPED", fixture.runtime.state());
    }

    @Test
    void jvmErrorFromProducerFailsRuntimeHealth() throws Exception {
        Fixture fixture = fixture(fastAssignment(), null);
        stubNormalBatch(fixture);
        doAnswer(ignored -> {
            throw new AssertionError("fatal producer failure");
        }).when(fixture.dispatch).dispatchTasks(any());

        fixture.runtime.start();

        await(Duration.ofSeconds(2), () ->
                "FAILED".equals(fixture.runtime.state())
        );
        assertFalse(fixture.runtime.isRunning());
        fixture.runtime.stop(2_000);
        assertEquals("STOPPED", fixture.runtime.state());
    }

    private static int complete(
            CountDownLatch rounds,
            AtomicBoolean allVirtual
    ) {
        allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
        rounds.countDown();
        return 0;
    }

    private static List<String> taskIds(List<ObservedTask> tasks) {
        return tasks.stream().map(ObservedTask::taskId).toList();
    }

    private static AssignmentDispatchConfig fastAssignment() {
        return AssignmentDispatchConfig.create(5, 5);
    }

    private static AssignmentDispatchConfig oneShotAssignment() {
        return AssignmentDispatchConfig.create(10_000, 10_000);
    }

    private static WorkerServiceabilityDispatchConfig
            enabledServiceability() {
        return new WorkerServiceabilityDispatchConfig(
                5,
                1_000,
                WorkerServiceabilityDispatchConfig
                        .DEFAULT_PROBE_RETRY_INTERVAL_MILLIS,
                WorkerServiceabilityDispatchConfig
                        .DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS,
                WorkerServiceabilityDispatchConfig
                        .DEFAULT_MAX_RECOVERY_ATTEMPTS,
                WorkerServiceabilityDispatchConfig
                        .DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
        );
    }

    private static TaskDescriptor descriptor(
            String taskId,
            String workerGroupId
    ) {
        return new TaskDescriptor(taskId, workerGroupId, TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "1"));
    }

    private static Fixture fixture(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchConfig serviceabilityConfig
    ) {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog taskCatalog = mock(TaskResourceCatalog.class);
        TaskInitializationPolicy initialization = mock(
                TaskInitializationPolicy.class
        );
        TaskDispatchPolicy dispatch = mock(TaskDispatchPolicy.class);
        WorkerServiceabilityDispatchPolicy serviceability = mock(
                WorkerServiceabilityDispatchPolicy.class
        );
        return new Fixture(
                new DispatchConvergenceRuntime(
                        new DispatchMainScheduler(
                                taskScores,
                                taskCatalog,
                                initialization,
                                dispatch,
                                serviceabilityConfig == null
                                        ? null
                                        : serviceability,
                                assignmentConfig,
                                serviceabilityConfig
                        )
                ),
                taskScores,
                taskCatalog,
                initialization,
                dispatch,
                serviceability
        );
    }

    private static void stubNormalBatch(Fixture fixture) {
        when(fixture.taskScores.acquireSchedulingTasks(100)).thenReturn(
                Map.of("task-normal", 101L)
        );
        when(fixture.taskScores.filterInitialTaskScores(any())).thenReturn(
                Map.of()
        );
        when(fixture.taskCatalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(Map.of(
                        "task-normal",
                        descriptor(
                                "task-normal",
                                "group-1"
                        )
                ));
    }

    private static void stubMixedBatch(Fixture fixture) {
        LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
        scores.put("task-normal", 101L);
        scores.put("task-initial", 100L);
        when(fixture.taskScores.acquireSchedulingTasks(100)).thenReturn(
                scores
        );
        when(fixture.taskScores.filterInitialTaskScores(any())).thenReturn(
                Map.of("task-initial", 100L)
        );
        when(fixture.taskCatalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(Map.of(
                        "task-normal",
                        descriptor(
                                "task-normal",
                                "group-1"
                        )
                ));
    }

    private static void stubProjectedBatch(Fixture fixture) {
        LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
        scores.put("task-first", 104L);
        scores.put("task-second", 103L);
        scores.put("task-repeat-group", 102L);
        scores.put("task-invalid", 101L);
        scores.put("task-initial", 100L);
        when(fixture.taskScores.acquireSchedulingTasks(100)).thenReturn(scores);
        when(fixture.taskScores.filterInitialTaskScores(any())).thenReturn(
                Map.of("task-initial", 100L)
        );
        when(fixture.taskCatalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(Map.of(
                        "task-first",
                        descriptor(
                                "task-first",
                                "group-1"
                        ),
                        "task-second",
                        descriptor(
                                "task-second",
                                "group-2"
                        ),
                        "task-repeat-group",
                        descriptor(
                                "task-repeat-group",
                                "group-1"
                        ),
                        "task-invalid",
                        descriptor(
                                "other-task",
                                "group-3"
                        )
                ));
    }

    private static void await(
            Duration timeout,
            BooleanSupplier condition
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private record Fixture(
            DispatchConvergenceRuntime runtime,
            TaskScoreBandCore taskScores,
            TaskResourceCatalog taskCatalog,
            TaskInitializationPolicy initialization,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability
    ) {
    }
}
