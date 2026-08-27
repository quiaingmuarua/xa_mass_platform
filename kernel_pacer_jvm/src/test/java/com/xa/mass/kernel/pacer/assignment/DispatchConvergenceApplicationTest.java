package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DispatchConvergenceApplicationTest {

    @Test
    void sharesOneRunningBatchAcrossThreeVirtualThreadLanes()
            throws Exception {
        Fixture fixture = fixture();
        List<DueTaskObservation> batch = List.of(observation());
        when(fixture.source.acquireTasks(anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new TaskSchedulingBatchSource.TaskSchedulingBatch(
                        batch,
                        batch
                ));
        CountDownLatch rounds = new CountDownLatch(4);
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        doAnswer(ignored -> complete(rounds, allVirtual))
                .when(fixture.allocation)
                .allocateCandidateWorkers(any(), any());
        doAnswer(ignored -> complete(rounds, allVirtual))
                .when(fixture.initialization)
                .initializeTasks(any());
        doAnswer(ignored -> complete(rounds, allVirtual))
                .when(fixture.dispatch)
                .dispatchTasks(any(), any());
        doAnswer(ignored -> complete(rounds, allVirtual))
                .when(fixture.serviceability)
                .dispatchProbes(any(), any(), any(Long.class));

        fixture.application.start(oneShotAssignment(), enabledServiceability());

        assertTrue(rounds.await(2, TimeUnit.SECONDS));
        assertTrue(allVirtual.get());
        verify(fixture.source).acquireTasks(100, true, true);
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
    void runtimeFailureIsLaneLocalAndLaterRoundsContinue()
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
                WorkerServiceabilityAssemblyConfig.disabled()
        );

        await(Duration.ofSeconds(2), () -> rounds.get() >= 2);
        assertTrue(fixture.application.isRunning());
        fixture.application.stop(2_000);
    }

    @Test
    void blockedAllocationDoesNotBlockOtherRunningLanesOrConsumeItselfAgain()
            throws Exception {
        Fixture fixture = fixture();
        stubNormalBatch(fixture);
        CountDownLatch allocationStarted = new CountDownLatch(1);
        CountDownLatch releaseAllocation = new CountDownLatch(1);
        CountDownLatch otherLanes = new CountDownLatch(2);
        AtomicInteger allocationRounds = new AtomicInteger();
        doAnswer(ignored -> {
            allocationRounds.incrementAndGet();
            allocationStarted.countDown();
            releaseAllocation.await(2, TimeUnit.SECONDS);
            return 0;
        }).when(fixture.allocation).allocateCandidateWorkers(any(), any());
        doAnswer(ignored -> {
            otherLanes.countDown();
            return 0;
        }).when(fixture.dispatch).dispatchTasks(any(), any());
        doAnswer(ignored -> {
            otherLanes.countDown();
            return 0;
        }).when(fixture.serviceability).dispatchProbes(
                any(), any(), any(Long.class)
        );

        fixture.application.start(fastAssignment(), enabledServiceability());

        assertTrue(allocationStarted.await(2, TimeUnit.SECONDS));
        assertTrue(otherLanes.await(2, TimeUnit.SECONDS));
        Thread.sleep(30);
        assertEquals(1, allocationRounds.get());
        releaseAllocation.countDown();
        fixture.application.stop(2_000);
    }

    @Test
    void jvmErrorFromPolicyFailsTheApplication() throws Exception {
        Fixture fixture = fixture();
        stubNormalBatch(fixture);
        doAnswer(ignored -> {
            throw new AssertionError("fatal policy failure");
        }).when(fixture.dispatch).dispatchTasks(any(), any());

        fixture.application.start(
                fastAssignment(),
                WorkerServiceabilityAssemblyConfig.disabled()
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

    private static AssignmentDispatchConfig fastAssignment() {
        return AssignmentDispatchConfig.create(5, 5, 5);
    }

    private static AssignmentDispatchConfig oneShotAssignment() {
        return AssignmentDispatchConfig.create(
                10_000,
                10_000,
                10_000
        );
    }

    private static WorkerServiceabilityAssemblyConfig
            enabledServiceability() {
        return new WorkerServiceabilityAssemblyConfig(
                true,
                1_000,
                WorkerServiceabilityResultConfig.defaults(),
                new WorkerServiceabilityDispatchLaneConfig(
                        5,
                        WorkerServiceabilityDispatchConfig.defaults()
                )
        );
    }

    private static DueTaskObservation observation() {
        String taskId = "task-1";
        return new DueTaskObservation(
                taskId,
                new TaskScoreState(
                        taskId,
                        1,
                        TaskScoreBand.RUNNING_VISIBLE,
                        100L,
                        0
                ),
                new TaskDescriptor(
                        taskId,
                        "group-1",
                        WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        null,
                        Map.of(
                                "priority", "0",
                                "maximumCandidateWorkers", "1",
                                "maxRetryTimes", "1"
                        )
                )
        );
    }

    private static Fixture fixture() {
        TaskSchedulingBatchSource source = mock(
                TaskSchedulingBatchSource.class
        );
        TaskInitializationPolicy initialization = mock(
                TaskInitializationPolicy.class
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
                        source,
                        initialization,
                        allocation,
                        dispatch,
                        serviceability
                ),
                source,
                initialization,
                allocation,
                dispatch,
                serviceability
        );
    }

    private static void stubNormalBatch(Fixture fixture) {
        when(fixture.source.acquireTasks(anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new TaskSchedulingBatchSource.TaskSchedulingBatch(
                        List.of(observation()),
                        List.of()
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
            TaskSchedulingBatchSource source,
            TaskInitializationPolicy initialization,
            TaskWorkerAllocationPolicy allocation,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability
    ) {
    }
}
