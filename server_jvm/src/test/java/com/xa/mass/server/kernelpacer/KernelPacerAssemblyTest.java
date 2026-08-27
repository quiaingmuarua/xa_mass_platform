package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KernelPacerAssemblyTest {

    @Test
    void disabledAssemblyDoesNotStartOrStopRuntime() {
        KernelPacerRuntime runtime = mock(KernelPacerRuntime.class);
        KernelPacerRuntime.Snapshot runtimeSnapshot = snapshot(
                KernelPacerRuntime.State.STOPPED
        );
        when(runtime.snapshot()).thenReturn(runtimeSnapshot);
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(false),
                runtime
        );

        assembly.start();
        assembly.stop();

        assertThat(assembly.isRunning()).isFalse();
        assertThat(assembly.snapshot()).isEqualTo(
                new KernelPacerAssembly.Snapshot(false, runtimeSnapshot)
        );
        verify(runtime, never()).start();
        verify(runtime, never()).stop();
    }

    @Test
    void enabledAssemblyDelegatesLifecycleAndHealthProjection() {
        KernelPacerRuntime runtime = mock(KernelPacerRuntime.class);
        when(runtime.isRunning()).thenReturn(true);
        when(runtime.snapshot()).thenReturn(snapshot(
                KernelPacerRuntime.State.RUNNING
        ));
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties(true),
                runtime
        );

        assembly.start();

        verify(runtime).start();
        assertThat(assembly.isRunning()).isTrue();
        var health = new KernelPacerHealthIndicator(assembly).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails())
                .containsEntry("javaResultConvergenceState", "RUNNING");

        assembly.destroy();
        verify(runtime).stop();
    }

    @Test
    void propertiesRejectUnsafeValues() {
        assertThatThrownBy(() -> new KernelPacerProperties(
                true,
                null,
                Duration.ofSeconds(1)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KernelPacerProperties(
                true,
                KernelPacerRuntime.PolicyPreset.DEFAULT,
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static KernelPacerProperties properties(boolean enabled) {
        return new KernelPacerProperties(
                enabled,
                KernelPacerRuntime.PolicyPreset.DEFAULT,
                Duration.ofSeconds(1)
        );
    }

    private static KernelPacerRuntime.Snapshot snapshot(
            KernelPacerRuntime.State state
    ) {
        return new KernelPacerRuntime.Snapshot(
                state,
                state.name(),
                state.name()
        );
    }
}
