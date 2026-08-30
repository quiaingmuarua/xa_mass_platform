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
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
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

class DispatchConvergenceApplicationTest {

    @Test
    void oneSourceObservationPlansAllFixedProducerInputs() throws Exception {
        Fixture fixture = fixture();
        stubProjectedBatch(fixture);
        CountDownLatch rounds = new CountDownLatch(4);
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        AtomicReference<List<String>> allocationTasks = new AtomicReference<>();
        AtomicReference<List<String>> dispatchedTasks = new AtomicReference<>();
        AtomicReference<List<String>> serviceabilityGroups =
                new AtomicReference<>();
        doAnswer(invocation -> {
            List<DueTaskObservation> tasks = invocation.getArgument(0);
            allocationTasks.set(taskIds(tasks));
            return complete(rounds, allVirtual);
        }).when(fixture.allocation).allocateCandidateWorkers(any(), any());
        doAnswer(ignored -> {
            complete(rounds, allVirtual);
            return null;
        }).when(fixture.initialization).check(any());
        doAnswer(invocation -> {
            List<DueTaskObservation> tasks = invocation.getArgument(0);
            dispatchedTasks.set(taskIds(tasks));
            return complete(rounds, allVirtual);
        }).when(fixture.dispatch).dispatchTasks(any(), any());
        doAnswer(invocation -> {
            List<String> groups = invocation.getArgument(0);
            serviceabilityGroups.set(groups);
            return complete(rounds, allVirtual);
        }).when(fixture.serviceability).dispatchProbes(
                any(), any(), any(Long.class)
        );

        fixture.application.start(oneShotAssignment(), enabledServiceability());

        assertTrue(rounds.await(2, TimeUnit.SECONDS));
        assertTrue(allVirtual.get());
        verify(fixture.taskScores).acquireSchedulingTasks(100);
        verify(fixture.taskScores).filterInitialTaskScores(any());
        verify(fixture.initialization).check(Map.of("task-initial", 100L));
        verify(fixture.taskCatalog).loadTaskAllocationDescriptors(List.of(
                "task-precomputed",
                "task-on-demand",
                "task-repeat-group",
                "task-invalid"
        ));
        assertEquals(List.of("task-precomputed"), allocationTasks.get());
        assertEquals(
                List.of(
                        "task-precomputed",
                        "task-on-demand",
                        "task-repeat-group"
                ),
                dispatchedTasks.get()
        );
        assertEquals(
                List.of("group-1", "group-2"),
                serviceabilityGroups.get()
        );
        assertTrue(fixture.application.isRunning());
        assertThrows(IllegalStateException.class, () ->
                fixture.application.start(
                        fastAssignment(),
                        enabledServiceability()
                )
        );

        fixture.application.stop(2_000);
        fixture.application.stop(2_000);
        assertEquals("STOPPED", fixture.application.state());
    }

    @Test
    void sourceFailureDefersEveryEligibleProducer() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch sourceAttempt = new CountDownLatch(1);
        when(fixture.taskScores.acquireSchedulingTasks(100)).thenAnswer(
                ignored -> {
                    sourceAttempt.countDown();
                    throw new IllegalStateException("source unavailable");
                }
        );

        fixture.application.start(oneShotAssignment(), enabledServiceability());

        assertTrue(sourceAttempt.await(2, TimeUnit.SECONDS));
        verify(fixture.initialization, never()).check(any());
        verify(fixture.allocation, never()).allocateCandidateWorkers(
                any(), any()
        );
        verify(fixture.dispatch, never()).dispatchTasks(any(), any());
        verify(fixture.serviceability, never()).dispatchProbes(
                any(), any(), any(Long.class)
        );
        assertTrue(fixture.application.isRunning());
        fixture.application.stop(2_000);
    }

    @Test
    void descriptorFailureDoesNotBlockFormedInitializationInput()
            throws Exception {
        Fixture fixture = fixture();
        stubMixedBatch(fixture);
        CountDownLatch initialized = new CountDownLatch(1);
        doAnswer(ignored -> {
            initialized.countDown();
            return null;
        }).when(fixture.initialization).check(any());
        when(fixture.taskCatalog.loadTaskAllocationDescriptors(any()))
                .thenThrow(new IllegalStateException("catalog unavailable"));

        fixture.application.start(
                oneShotAssignment(),
                WorkerServiceabilityDispatchAssemblyConfig.disabled()
        );

        assertTrue(initialized.await(2, TimeUnit.SECONDS));
        verify(fixture.initialization).check(Map.of("task-initial", 100L));
        verify(fixture.allocation, never()).allocateCandidateWorkers(
                any(), any()
        );
        verify(fixture.dispatch, never()).dispatchTasks(any(), any());
        assertTrue(fixture.application.isRunning());
        fixture.application.stop(2_000);
    }

    @Test
    void runtimeFailureIsProducerLocalAndLaterRoundsContinue()
            throws Exception {
        Fixture fixture = fixture();
        stubNormalBatch(fixture);
        AtomicInteger rounds = new AtomicInteger();
        doAnswer(ignored -> {
            if (rounds.incrementAndGet() == 1) {
                throw new IllegalStateException("round failure");
            }
            return 0;
        }).when(fixture.allocation).allocateCandidateWorkers(any(), any());

        fixture.application.start(
                fastAssignment(),
                WorkerServiceabilityDispatchAssemblyConfig.disabled()
        );

        await(Duration.ofSeconds(2), () -> rounds.get() >= 2);
        assertTrue(fixture.application.isRunning());
        fixture.application.stop(2_000);
    }

    @Test
    void persistentlyDueNormalTaskIsRediscoveredAcrossCadences()
            throws Exception {
        Fixture fixture = fixture();
        stubNormalBatch(fixture);
        CountDownLatch dispatchRounds = new CountDownLatch(3);
        doAnswer(ignored -> {
            dispatchRounds.countDown();
            return 1;
        }).when(fixture.dispatch).dispatchTasks(any(), any());

        fixture.application.start(
                fastAssignment(),
                WorkerServiceabilityDispatchAssemblyConfig.disabled()
        );

        assertTrue(dispatchRounds.await(2, TimeUnit.SECONDS));
        verify(fixture.taskScores, atLeast(3)).acquireSchedulingTasks(100);
        fixture.application.stop(2_000);
    }

    @Test
    void blockedAllocationDoesNotBlockOtherProducersOrReenter()
            throws Exception {
        Fixture fixture = fixture();
        stubNormalBatch(fixture);
        CountDownLatch allocationStarted = new CountDownLatch(1);
        CountDownLatch releaseAllocation = new CountDownLatch(1);
        CountDownLatch otherProducers = new CountDownLatch(2);
        AtomicInteger allocationRounds = new AtomicInteger();
        doAnswer(ignored -> {
            allocationRounds.incrementAndGet();
            allocationStarted.countDown();
            releaseAllocation.await(2, TimeUnit.SECONDS);
            return 0;
        }).when(fixture.allocation).allocateCandidateWorkers(any(), any());
        doAnswer(ignored -> {
            otherProducers.countDown();
            return 0;
        }).when(fixture.dispatch).dispatchTasks(any(), any());
        doAnswer(ignored -> {
            otherProducers.countDown();
            return 0;
        }).when(fixture.serviceability).dispatchProbes(
                any(), any(), any(Long.class)
        );

        fixture.application.start(fastAssignment(), enabledServiceability());

        assertTrue(allocationStarted.await(2, TimeUnit.SECONDS));
        assertTrue(otherProducers.await(2, TimeUnit.SECONDS));
        Thread.sleep(30);
        assertEquals(1, allocationRounds.get());
        releaseAllocation.countDown();
        fixture.application.stop(2_000);
    }

    @Test
    void blockedInitializationDoesNotBlockNormalProducersOrReenter()
            throws Exception {
        Fixture fixture = fixture();
        stubMixedBatch(fixture);
        CountDownLatch initializationStarted = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        CountDownLatch normalProducers = new CountDownLatch(2);
        AtomicInteger initializationRounds = new AtomicInteger();
        doAnswer(ignored -> {
            initializationRounds.incrementAndGet();
            initializationStarted.countDown();
            releaseInitialization.await(2, TimeUnit.SECONDS);
            return null;
        }).when(fixture.initialization).check(any());
        doAnswer(ignored -> {
            normalProducers.countDown();
            return 0;
        }).when(fixture.allocation).allocateCandidateWorkers(any(), any());
        doAnswer(ignored -> {
            normalProducers.countDown();
            return 0;
        }).when(fixture.dispatch).dispatchTasks(any(), any());

        fixture.application.start(
                fastAssignment(),
                WorkerServiceabilityDispatchAssemblyConfig.disabled()
        );

        assertTrue(initializationStarted.await(2, TimeUnit.SECONDS));
        assertTrue(normalProducers.await(2, TimeUnit.SECONDS));
        Thread.sleep(30);
        assertEquals(1, initializationRounds.get());
        releaseInitialization.countDown();
        fixture.application.stop(2_000);
    }

    @Test
    void jvmErrorFromProducerFailsTheApplication() throws Exception {
        Fixture fixture = fixture();
        stubNormalBatch(fixture);
        doAnswer(ignored -> {
            throw new AssertionError("fatal producer failure");
        }).when(fixture.dispatch).dispatchTasks(any(), any());

        fixture.application.start(
                fastAssignment(),
                WorkerServiceabilityDispatchAssemblyConfig.disabled()
        );

        await(Duration.ofSeconds(2), () ->
                "FAILED".equals(fixture.application.state())
        );
        assertFalse(fixture.application.isRunning());
        fixture.application.stop(2_000);
        assertEquals("STOPPED", fixture.application.state());
    }

    private static int complete(
            CountDownLatch rounds,
            AtomicBoolean allVirtual
    ) {
        allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
        rounds.countDown();
        return 0;
    }

    private static List<String> taskIds(List<DueTaskObservation> tasks) {
        return tasks.stream().map(DueTaskObservation::taskId).toList();
    }

    private static AssignmentDispatchConfig fastAssignment() {
        return AssignmentDispatchConfig.create(5, 5, 5);
    }

    private static AssignmentDispatchConfig oneShotAssignment() {
        return AssignmentDispatchConfig.create(10_000, 10_000, 10_000);
    }

    private static WorkerServiceabilityDispatchAssemblyConfig
            enabledServiceability() {
        return new WorkerServiceabilityDispatchAssemblyConfig(
                true,
                1_000,
                5,
                WorkerServiceabilityDispatchConfig.defaults()
        );
    }

    private static TaskDescriptor descriptor(
            String taskId,
            String workerGroupId,
            WorkerAllocationMechanism mechanism
    ) {
        return new TaskDescriptor(
                taskId,
                workerGroupId,
                mechanism,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                mechanism == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                        ? Map.of()
                        : null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "1"
                )
        );
    }

    private static Fixture fixture() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog taskCatalog = mock(TaskResourceCatalog.class);
        TaskInitializationCheck initialization = mock(
                TaskInitializationCheck.class
        );
        TaskWorkerAllocationPolicy allocation = mock(
                TaskWorkerAllocationPolicy.class
        );
        TaskDispatchPolicy dispatch = mock(TaskDispatchPolicy.class);
        WorkerServiceabilityDispatchPolicy serviceability = mock(
                WorkerServiceabilityDispatchPolicy.class
        );
        return new Fixture(
                new DispatchConvergenceApplication(
                        taskScores,
                        taskCatalog,
                        initialization,
                        allocation,
                        dispatch,
                        serviceability
                ),
                taskScores,
                taskCatalog,
                initialization,
                allocation,
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
                                "group-1",
                                WorkerAllocationMechanism
                                        .PRECOMPUTED_TASK_RULE
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
                                "group-1",
                                WorkerAllocationMechanism
                                        .PRECOMPUTED_TASK_RULE
                        )
                ));
    }

    private static void stubProjectedBatch(Fixture fixture) {
        LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
        scores.put("task-precomputed", 104L);
        scores.put("task-on-demand", 103L);
        scores.put("task-repeat-group", 102L);
        scores.put("task-invalid", 101L);
        scores.put("task-initial", 100L);
        when(fixture.taskScores.acquireSchedulingTasks(100)).thenReturn(scores);
        when(fixture.taskScores.filterInitialTaskScores(any())).thenReturn(
                Map.of("task-initial", 100L)
        );
        when(fixture.taskCatalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(Map.of(
                        "task-precomputed",
                        descriptor(
                                "task-precomputed",
                                "group-1",
                                WorkerAllocationMechanism
                                        .PRECOMPUTED_TASK_RULE
                        ),
                        "task-on-demand",
                        descriptor(
                                "task-on-demand",
                                "group-2",
                                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE
                        ),
                        "task-repeat-group",
                        descriptor(
                                "task-repeat-group",
                                "group-1",
                                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE
                        ),
                        "task-invalid",
                        descriptor(
                                "other-task",
                                "group-3",
                                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE
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
            DispatchConvergenceApplication application,
            TaskScoreBandCore taskScores,
            TaskResourceCatalog taskCatalog,
            TaskInitializationCheck initialization,
            TaskWorkerAllocationPolicy allocation,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability
    ) {
    }
}
