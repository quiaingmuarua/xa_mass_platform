package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class KernelPacerAssemblyTest {

    @Test
    void disabledAssemblyDoesNotStartAChildAndIsDown() {
        PythonKernelPacerProcess process = mock(
                PythonKernelPacerProcess.class
        );
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(false),
                process
        );

        assembly.start();

        assertThat(assembly.snapshot())
                .isEqualTo(new KernelPacerAssembly.Snapshot(
                        false,
                        KernelPacerAssembly.State.STOPPED,
                        null
                ));
        assertThat(assembly.getPhase()).isEqualTo(Integer.MIN_VALUE);
        assertThat(new KernelPacerHealthIndicator(assembly)
                .health().getStatus().getCode()).isEqualTo("DOWN");
        verify(process, never()).start();
    }

    @Test
    void runningChildOwnsReadinessAndStopsOnce() {
        PythonKernelPacerProcess process = mock(
                PythonKernelPacerProcess.class
        );
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(42L);
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(true),
                process
        );

        assembly.start();

        assertThat(assembly.isRunning()).isTrue();
        assertThat(assembly.snapshot().pid()).isEqualTo(42L);
        assertThat(new KernelPacerHealthIndicator(assembly)
                .health().getStatus().getCode()).isEqualTo("UP");

        assembly.stop();
        assembly.stop();

        assertThat(assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.STOPPED);
        verify(process).start();
        verify(process).stop();
    }

    @Test
    void unexpectedExitChangesReadinessWithoutRestarting() {
        PythonKernelPacerProcess process = mock(
                PythonKernelPacerProcess.class
        );
        when(process.isAlive()).thenReturn(true, false);
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(true),
                process
        );
        assembly.start();

        assertThat(assembly.isRunning()).isTrue();
        assertThat(assembly.isRunning()).isFalse();
        assertThat(assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.FAILED);
        assertThat(new KernelPacerHealthIndicator(assembly)
                .health().getStatus().getCode()).isEqualTo("DOWN");
        verify(process).start();

        assembly.destroy();

        verify(process).stop();
    }

    @Test
    void startupFailureIsFailFast() {
        PythonKernelPacerProcess process = mock(
                PythonKernelPacerProcess.class
        );
        doThrow(new IllegalStateException("failed"))
                .when(process).start();
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(true),
                process
        );

        assertThatThrownBy(assembly::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");
        assertThat(assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.FAILED);
    }

    @Test
    void shutdownFailureRemainsFailedAndCanBeRetried() {
        PythonKernelPacerProcess process = mock(
                PythonKernelPacerProcess.class
        );
        when(process.isAlive()).thenReturn(true);
        doThrow(new IllegalStateException("still alive"))
                .doNothing()
                .when(process).stop();
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(true),
                process
        );
        assembly.start();

        assertThatThrownBy(assembly::stop)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("still alive");
        assertThat(assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.FAILED);

        assembly.stop();
        assertThat(assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.STOPPED);
        verify(process, org.mockito.Mockito.times(2)).stop();
    }

    @Test
    void propertiesRejectUnsafeEmptyOrNonPositiveValues() {
        assertThatThrownBy(() -> new KernelPacerProperties(
                true,
                " ",
                ".",
                "kernel.json",
                "state",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KernelPacerProperties(
                true,
                "python",
                ".",
                "kernel.json",
                "state",
                Duration.ZERO,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static KernelPacerProperties properties(boolean enabled) {
        return new KernelPacerProperties(
                enabled,
                "python",
                ".",
                "kernel.json",
                "state",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }
}
