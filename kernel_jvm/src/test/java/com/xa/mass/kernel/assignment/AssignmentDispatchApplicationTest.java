package com.xa.mass.kernel.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class AssignmentDispatchApplicationTest {

    @Test
    void startsAllThreeLoopsImmediatelyAndStopsThemTogether()
            throws Exception {
        TaskWorkerAllocationPacer allocation = mock(
                TaskWorkerAllocationPacer.class
        );
        TaskRunningActivationPacer activation = mock(
                TaskRunningActivationPacer.class
        );
        TaskDispatchPacer dispatch = mock(TaskDispatchPacer.class);
        CountDownLatch rounds = new CountDownLatch(3);
        doAnswer(ignored -> {
            rounds.countDown();
            return 0;
        }).when(allocation).allocateCandidateWorkers(any());
        doAnswer(ignored -> {
            rounds.countDown();
            return 0;
        }).when(activation).activateRunningVisibleTasks(any());
        doAnswer(ignored -> {
            rounds.countDown();
            return 0;
        }).when(dispatch).dispatchTasks(any());
        AssignmentDispatchApplication application =
                new AssignmentDispatchApplication(
                        allocation,
                        activation,
                        dispatch
                );

        application.start(AssignmentDispatchApplicationConfig.defaults());

        assertTrue(rounds.await(
                2,
                java.util.concurrent.TimeUnit.SECONDS
        ));
        assertTrue(application.isRunning());
        assertThrows(IllegalStateException.class, () -> application.start(
                AssignmentDispatchApplicationConfig.defaults()
        ));

        application.stop(2_000);
        application.stop(2_000);
        assertEquals("STOPPED", application.state());
    }

    @Test
    void runtimeFailureIsIsolatedAndLaterRoundsContinue()
            throws Exception {
        TaskWorkerAllocationPacer allocation = mock(
                TaskWorkerAllocationPacer.class
        );
        TaskRunningActivationPacer activation = mock(
                TaskRunningActivationPacer.class
        );
        TaskDispatchPacer dispatch = mock(TaskDispatchPacer.class);
        AtomicInteger allocationRounds = new AtomicInteger();
        doAnswer(ignored -> {
            int round = allocationRounds.incrementAndGet();
            if (round == 1) {
                throw new IllegalStateException("round failure");
            }
            return 0;
        }).when(allocation).allocateCandidateWorkers(any());
        doAnswer(ignored -> 0)
                .when(activation).activateRunningVisibleTasks(any());
        doAnswer(ignored -> 0).when(dispatch).dispatchTasks(any());
        AssignmentDispatchApplication application =
                new AssignmentDispatchApplication(
                        allocation,
                        activation,
                        dispatch
                );

        application.start(new AssignmentDispatchApplicationConfig(
                10,
                10,
                10,
                AssignmentDispatchApplicationConfig.defaults()
                        .workerAllocation(),
                AssignmentDispatchApplicationConfig.defaults()
                        .runningActivation(),
                AssignmentDispatchApplicationConfig.defaults().taskDispatch()
        ));

        await(Duration.ofSeconds(2), () -> allocationRounds.get() >= 2);
        assertTrue(allocationRounds.get() >= 2);
        assertTrue(application.isRunning());
        application.stop(2_000);
        assertEquals("STOPPED", application.state());
    }

    @Test
    void jvmErrorTerminatesOneLoopAndFailsTheApplication()
            throws Exception {
        TaskWorkerAllocationPacer allocation = mock(
                TaskWorkerAllocationPacer.class
        );
        TaskRunningActivationPacer activation = mock(
                TaskRunningActivationPacer.class
        );
        TaskDispatchPacer dispatch = mock(TaskDispatchPacer.class);
        doAnswer(ignored -> 0)
                .when(allocation).allocateCandidateWorkers(any());
        doAnswer(ignored -> 0)
                .when(activation).activateRunningVisibleTasks(any());
        doAnswer(ignored -> {
            throw new AssertionError("fatal loop failure");
        }).when(dispatch).dispatchTasks(any());
        AssignmentDispatchApplication application =
                new AssignmentDispatchApplication(
                        allocation,
                        activation,
                        dispatch
                );

        application.start(AssignmentDispatchApplicationConfig.defaults());

        await(Duration.ofSeconds(2), () ->
                "FAILED".equals(application.state())
        );
        assertFalse(application.isRunning());
        application.stop(2_000);
        assertEquals("STOPPED", application.state());
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
}
