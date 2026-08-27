package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KernelPacerRuntimeTest {

    @Test
    void startsThreeStagesAndStopsInStrictReverseOrder() {
        Fixture fixture = fixture(enabledServiceability());
        when(fixture.resultConvergence.isRunning()).thenReturn(true);
        when(fixture.resultConvergence.state()).thenReturn("RUNNING");
        when(fixture.serviceabilityDispatch.isRunning()).thenReturn(true);
        when(fixture.serviceabilityDispatch.state()).thenReturn("RUNNING");
        when(fixture.assignmentDispatch.isRunning()).thenReturn(true);
        when(fixture.assignmentDispatch.state()).thenReturn("RUNNING");

        fixture.runtime.start();
        assertTrue(fixture.runtime.isRunning());
        assertEquals(
                KernelPacerRuntime.State.RUNNING,
                fixture.runtime.snapshot().state()
        );
        fixture.runtime.stop();

        InOrder order = inOrder(
                fixture.resultConvergence,
                fixture.serviceabilityDispatch,
                fixture.assignmentDispatch
        );
        order.verify(fixture.resultConvergence).start();
        order.verify(fixture.serviceabilityDispatch).start(
                fixture.serviceability.dispatch(),
                1_000L
        );
        order.verify(fixture.assignmentDispatch).start(
                fixture.policy.assignmentDispatch()
        );
        order.verify(fixture.assignmentDispatch).stop(anyLong());
        order.verify(fixture.serviceabilityDispatch).stop(anyLong());
        order.verify(fixture.resultConvergence).stop(anyLong());
        assertEquals(
                KernelPacerRuntime.State.STOPPED,
                fixture.runtime.snapshot().state()
        );
    }

    @Test
    void assignmentStartFailureRollsBackEarlierStages() {
        Fixture fixture = fixture(enabledServiceability());
        doThrow(new IllegalStateException("assignment failed"))
                .when(fixture.assignmentDispatch)
                .start(fixture.policy.assignmentDispatch());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                fixture.runtime::start
        );
        assertEquals("assignment failed", failure.getMessage());

        InOrder order = inOrder(
                fixture.serviceabilityDispatch,
                fixture.resultConvergence
        );
        order.verify(fixture.serviceabilityDispatch).stop(anyLong());
        order.verify(fixture.resultConvergence).stop(anyLong());
        assertEquals(
                KernelPacerRuntime.State.FAILED,
                fixture.runtime.snapshot().state()
        );
    }

    @Test
    void reverseShutdownSharesOneDecreasingBudget() throws Exception {
        Fixture fixture = fixture(enabledServiceability());
        AtomicLong assignmentBudget = new AtomicLong();
        AtomicLong dispatchBudget = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocation -> {
            assignmentBudget.set(invocation.getArgument(0));
            Thread.sleep(25);
            return null;
        }).when(fixture.assignmentDispatch).stop(anyLong());
        org.mockito.Mockito.doAnswer(invocation -> {
            dispatchBudget.set(invocation.getArgument(0));
            return null;
        }).when(fixture.serviceabilityDispatch).stop(anyLong());
        fixture.runtime.start();

        fixture.runtime.stop();

        assertTrue(assignmentBudget.get() > 0);
        assertTrue(dispatchBudget.get() > 0);
        assertTrue(dispatchBudget.get() < assignmentBudget.get());
    }

    @Test
    void unexpectedApplicationExitMakesRuntimeFailed() {
        Fixture fixture = fixture(enabledServiceability());
        when(fixture.resultConvergence.isRunning()).thenReturn(true);
        when(fixture.serviceabilityDispatch.isRunning()).thenReturn(true);
        when(fixture.assignmentDispatch.isRunning()).thenReturn(true, false);
        when(fixture.assignmentDispatch.state()).thenReturn("FAILED");
        fixture.runtime.start();

        assertTrue(fixture.runtime.isRunning());
        assertFalse(fixture.runtime.isRunning());
        assertEquals(
                KernelPacerRuntime.State.FAILED,
                fixture.runtime.snapshot().state()
        );

        fixture.runtime.stop();
        verify(fixture.assignmentDispatch).stop(anyLong());
    }

    @Test
    void absentServiceabilityStartsOnlyRequiredStages() {
        Fixture fixture = fixture(disabledServiceability());
        when(fixture.resultConvergence.isRunning()).thenReturn(true);
        when(fixture.assignmentDispatch.isRunning()).thenReturn(true);
        fixture.runtime.start();

        verify(fixture.serviceabilityDispatch, never()).start(
                org.mockito.ArgumentMatchers.any(),
                anyLong()
        );
        assertEquals(
                "DISABLED",
                fixture.runtime.snapshot()
                        .workerServiceabilityDispatchState()
        );

        fixture.runtime.stop();
    }

    @Test
    void runtimeRejectsNonPositiveSharedShutdownBudget() {
        Fixture fixture = fixture(disabledServiceability());
        assertThrows(
                IllegalArgumentException.class,
                () -> new KernelPacerRuntime(
                        Duration.ZERO,
                        fixture.policy,
                        fixture.resultConvergence,
                        fixture.serviceabilityDispatch,
                        fixture.assignmentDispatch
                )
        );
    }

    private static Fixture fixture(
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
        ResultConvergenceApplication convergence = mock(
                ResultConvergenceApplication.class
        );
        WorkerServiceabilityDispatchApplication dispatch = mock(
                WorkerServiceabilityDispatchApplication.class
        );
        AssignmentDispatchApplication assignment = mock(
                AssignmentDispatchApplication.class
        );
        when(convergence.state()).thenReturn("STOPPED");
        when(dispatch.state()).thenReturn("STOPPED");
        when(assignment.state()).thenReturn("STOPPED");
        KernelPacerPolicyConfig policy = new KernelPacerPolicyConfig(
                ResultConvergenceConfig.defaults(),
                serviceability,
                AssignmentDispatchApplicationConfig.defaults()
        );
        KernelPacerRuntime runtime = new KernelPacerRuntime(
                Duration.ofSeconds(1),
                policy,
                convergence,
                dispatch,
                assignment
        );
        return new Fixture(
                runtime,
                convergence,
                dispatch,
                assignment,
                policy,
                serviceability
        );
    }

    private static WorkerServiceabilityAssemblyConfig enabledServiceability() {
        return new WorkerServiceabilityAssemblyConfig(
                true,
                1_000,
                WorkerServiceabilityResultConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    private static WorkerServiceabilityAssemblyConfig disabledServiceability() {
        return new WorkerServiceabilityAssemblyConfig(
                false,
                0,
                WorkerServiceabilityResultConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    private record Fixture(
            KernelPacerRuntime runtime,
            ResultConvergenceApplication resultConvergence,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            AssignmentDispatchApplication assignmentDispatch,
            KernelPacerPolicyConfig policy,
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
    }
}
