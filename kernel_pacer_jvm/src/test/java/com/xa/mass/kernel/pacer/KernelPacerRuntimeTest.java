package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.dispatch.DispatchConvergenceRuntime;
import com.xa.mass.kernel.pacer.result.ResultConvergenceRuntime;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KernelPacerRuntimeTest {

    @Test
    void startsResultThenDispatchAndStopsInStrictReverseOrder() {
        Fixture fixture = fixture();
        when(fixture.resultConvergence.isRunning()).thenReturn(true);
        when(fixture.resultConvergence.state()).thenReturn("RUNNING");
        when(fixture.dispatchConvergence.isRunning()).thenReturn(true);
        when(fixture.dispatchConvergence.state()).thenReturn("RUNNING");

        fixture.runtime.start();
        assertTrue(fixture.runtime.isRunning());
        assertEquals(
                KernelPacerRuntime.State.RUNNING,
                fixture.runtime.snapshot().state()
        );
        fixture.runtime.stop();

        InOrder order = inOrder(
                fixture.resultConvergence,
                fixture.dispatchConvergence
        );
        order.verify(fixture.resultConvergence).start();
        order.verify(fixture.dispatchConvergence).start();
        order.verify(fixture.dispatchConvergence).stop(anyLong());
        order.verify(fixture.resultConvergence).stop(anyLong());
        assertEquals(
                KernelPacerRuntime.State.STOPPED,
                fixture.runtime.snapshot().state()
        );
    }

    @Test
    void dispatchStartFailureRollsBackResultConvergence() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("dispatch failed"))
                .when(fixture.dispatchConvergence)
                .start();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                fixture.runtime::start
        );
        assertEquals("dispatch failed", failure.getMessage());
        verify(fixture.resultConvergence).stop(anyLong());
        assertEquals(
                KernelPacerRuntime.State.FAILED,
                fixture.runtime.snapshot().state()
        );
    }

    @Test
    void reverseShutdownSharesOneDecreasingBudget() throws Exception {
        Fixture fixture = fixture();
        AtomicLong dispatchBudget = new AtomicLong();
        AtomicLong resultBudget = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocation -> {
            dispatchBudget.set(invocation.getArgument(0));
            Thread.sleep(25);
            return null;
        }).when(fixture.dispatchConvergence).stop(anyLong());
        org.mockito.Mockito.doAnswer(invocation -> {
            resultBudget.set(invocation.getArgument(0));
            return null;
        }).when(fixture.resultConvergence).stop(anyLong());
        fixture.runtime.start();

        fixture.runtime.stop();

        assertTrue(dispatchBudget.get() > 0);
        assertTrue(resultBudget.get() > 0);
        assertTrue(resultBudget.get() < dispatchBudget.get());
    }

    @Test
    void unexpectedDispatchExitMakesRuntimeFailed() {
        Fixture fixture = fixture();
        when(fixture.resultConvergence.isRunning()).thenReturn(true);
        when(fixture.dispatchConvergence.isRunning()).thenReturn(true, false);
        when(fixture.dispatchConvergence.state()).thenReturn("FAILED");
        fixture.runtime.start();

        assertTrue(fixture.runtime.isRunning());
        assertFalse(fixture.runtime.isRunning());
        assertEquals(
                KernelPacerRuntime.State.FAILED,
                fixture.runtime.snapshot().state()
        );

        fixture.runtime.stop();
        verify(fixture.dispatchConvergence).stop(anyLong());
    }

    @Test
    void snapshotUsesUnifiedDispatchState() {
        Fixture fixture = fixture();
        when(fixture.resultConvergence.state()).thenReturn("STOPPED");
        when(fixture.dispatchConvergence.state()).thenReturn("STOPPED");

        assertEquals(
                "STOPPED",
                fixture.runtime.snapshot().dispatchConvergenceState()
        );
    }

    @Test
    void runtimeRejectsNonPositiveSharedShutdownBudget() {
        Fixture fixture = fixture();
        assertThrows(
                IllegalArgumentException.class,
                () -> new KernelPacerRuntime(
                        Duration.ZERO,
                        fixture.resultConvergence,
                        fixture.dispatchConvergence
                )
        );
    }

    private static Fixture fixture() {
        ResultConvergenceRuntime result = mock(
                ResultConvergenceRuntime.class
        );
        DispatchConvergenceRuntime dispatch = mock(
                DispatchConvergenceRuntime.class
        );
        when(result.state()).thenReturn("STOPPED");
        when(dispatch.state()).thenReturn("STOPPED");
        KernelPacerRuntime runtime = new KernelPacerRuntime(
                Duration.ofSeconds(1),
                result,
                dispatch
        );
        return new Fixture(runtime, result, dispatch);
    }

    private record Fixture(
            KernelPacerRuntime runtime,
            ResultConvergenceRuntime resultConvergence,
            DispatchConvergenceRuntime dispatchConvergence
    ) {
    }
}
