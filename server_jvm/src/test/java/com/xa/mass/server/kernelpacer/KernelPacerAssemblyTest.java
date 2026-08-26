package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.result.ResultRoutingApplication;
import com.xa.mass.kernel.result.ResultRoutingApplicationConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityAssemblyConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchApplication;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchApplicationConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultApplication;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultApplicationConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KernelPacerAssemblyTest {

    @Test
    void disabledAssemblyStartsNothingAndReportsDisabledApplications() {
        Fixture fixture = fixture(false, disabledServiceability());

        fixture.assembly.start();

        assertThat(fixture.assembly.snapshot()).isEqualTo(
                new KernelPacerAssembly.Snapshot(
                        false,
                        KernelPacerAssembly.State.STOPPED,
                        null,
                        "STOPPED",
                        "DISABLED",
                        "DISABLED"
                )
        );
        verify(fixture.python, never()).start();
        verify(fixture.resultRouting, never()).start(any());
        verify(fixture.serviceabilityResult, never())
                .start(any(), any(Long.class));
        verify(fixture.serviceabilityDispatch, never())
                .start(any(), any(Long.class));
    }

    @Test
    void startsFourStagesAndStopsInStrictReverseOrder() {
        Fixture fixture = fixture(true, enabledServiceability());
        when(fixture.python.isAlive()).thenReturn(true);
        when(fixture.python.pid()).thenReturn(42L);
        when(fixture.resultRouting.isRunning()).thenReturn(true);
        when(fixture.resultRouting.state()).thenReturn("RUNNING");
        when(fixture.serviceabilityResult.isRunning()).thenReturn(true);
        when(fixture.serviceabilityResult.state()).thenReturn("RUNNING");
        when(fixture.serviceabilityDispatch.isRunning()).thenReturn(true);
        when(fixture.serviceabilityDispatch.state()).thenReturn("RUNNING");

        fixture.assembly.start();
        assertThat(fixture.assembly.isRunning()).isTrue();
        assertThat(fixture.assembly.snapshot().pid()).isEqualTo(42L);
        assertThat(new KernelPacerHealthIndicator(fixture.assembly)
                .health().getStatus().getCode()).isEqualTo("UP");
        fixture.assembly.stop();

        InOrder order = inOrder(
                fixture.resultRouting,
                fixture.serviceabilityResult,
                fixture.serviceabilityDispatch,
                fixture.python
        );
        order.verify(fixture.resultRouting).start(fixture.resultConfig);
        order.verify(fixture.serviceabilityResult).start(
                fixture.serviceability.result(),
                1_000L
        );
        order.verify(fixture.serviceabilityDispatch).start(
                fixture.serviceability.dispatch(),
                1_000L
        );
        order.verify(fixture.python).start();
        order.verify(fixture.python).stop(any(Duration.class));
        order.verify(fixture.serviceabilityDispatch).stop(any(Long.class));
        order.verify(fixture.serviceabilityResult).stop(any(Long.class));
        order.verify(fixture.resultRouting).stop(any(Long.class));
    }

    @Test
    void dispatchStartFailureRollsBackEarlierApplicationsWithoutPython() {
        Fixture fixture = fixture(true, enabledServiceability());
        doThrow(new IllegalStateException("dispatch failed"))
                .when(fixture.serviceabilityDispatch)
                .start(fixture.serviceability.dispatch(), 1_000L);

        assertThatThrownBy(fixture.assembly::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dispatch failed");

        verify(fixture.python, never()).start();
        verify(fixture.serviceabilityResult).stop(any(Long.class));
        verify(fixture.resultRouting).stop(any(Long.class));
        assertThat(fixture.assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.FAILED);
    }

    @Test
    void pythonStartFailureRollsBackAllJavaApplications() {
        Fixture fixture = fixture(true, enabledServiceability());
        doThrow(new IllegalStateException("python failed"))
                .when(fixture.python).start();

        assertThatThrownBy(fixture.assembly::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("python failed");

        InOrder order = inOrder(
                fixture.serviceabilityDispatch,
                fixture.serviceabilityResult,
                fixture.resultRouting
        );
        order.verify(fixture.serviceabilityDispatch).stop(any(Long.class));
        order.verify(fixture.serviceabilityResult).stop(any(Long.class));
        order.verify(fixture.resultRouting).stop(any(Long.class));
    }

    @Test
    void unexpectedDispatchExitMakesAggregateReadinessDown() {
        Fixture fixture = fixture(true, enabledServiceability());
        when(fixture.python.isAlive()).thenReturn(true);
        when(fixture.resultRouting.isRunning()).thenReturn(true);
        when(fixture.serviceabilityResult.isRunning()).thenReturn(true);
        when(fixture.serviceabilityDispatch.isRunning())
                .thenReturn(true, false);
        when(fixture.serviceabilityDispatch.state()).thenReturn("FAILED");
        fixture.assembly.start();

        assertThat(fixture.assembly.isRunning()).isTrue();
        assertThat(fixture.assembly.isRunning()).isFalse();
        assertThat(fixture.assembly.snapshot()
                .workerServiceabilityDispatchState()).isEqualTo("FAILED");
        assertThat(new KernelPacerHealthIndicator(fixture.assembly)
                .health().getStatus().getCode()).isEqualTo("DOWN");

        fixture.assembly.destroy();
        verify(fixture.serviceabilityDispatch).stop(any(Long.class));
    }

    @Test
    void serviceabilityAbsentStartsOnlyResultRoutingAndPython() {
        Fixture fixture = fixture(true, disabledServiceability());
        when(fixture.python.isAlive()).thenReturn(true);
        when(fixture.resultRouting.isRunning()).thenReturn(true);
        fixture.assembly.start();

        verify(fixture.serviceabilityResult, never())
                .start(any(), any(Long.class));
        verify(fixture.serviceabilityDispatch, never())
                .start(any(), any(Long.class));
        assertThat(fixture.assembly.snapshot()
                .workerServiceabilityResultState()).isEqualTo("DISABLED");
        assertThat(fixture.assembly.snapshot()
                .workerServiceabilityDispatchState()).isEqualTo("DISABLED");

        fixture.assembly.stop();
    }

    @Test
    void propertiesRejectUnsafeValues() {
        assertThatThrownBy(() -> new KernelPacerProperties(
                true,
                " ",
                ".",
                "kernel.json",
                "state",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(
            boolean enabled,
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
        KernelPacerProperties properties = new KernelPacerProperties(
                enabled,
                "python",
                ".",
                "kernel.json",
                "state",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        PythonKernelPacerProcess python = mock(PythonKernelPacerProcess.class);
        ResultRoutingApplication resultRouting = mock(
                ResultRoutingApplication.class
        );
        WorkerServiceabilityResultApplication result = mock(
                WorkerServiceabilityResultApplication.class
        );
        WorkerServiceabilityDispatchApplication dispatch = mock(
                WorkerServiceabilityDispatchApplication.class
        );
        when(resultRouting.state()).thenReturn("STOPPED");
        when(result.state()).thenReturn("STOPPED");
        when(dispatch.state()).thenReturn("STOPPED");
        ResultRoutingApplicationConfig resultConfig =
                ResultRoutingApplicationConfig.fromKernelConfigJson("{}");
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties,
                python,
                resultRouting,
                resultConfig,
                result,
                dispatch,
                serviceability
        );
        return new Fixture(
                assembly,
                python,
                resultRouting,
                result,
                dispatch,
                resultConfig,
                serviceability
        );
    }

    private static WorkerServiceabilityAssemblyConfig enabledServiceability() {
        return new WorkerServiceabilityAssemblyConfig(
                true,
                1_000,
                WorkerServiceabilityResultApplicationConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    private static WorkerServiceabilityAssemblyConfig disabledServiceability() {
        return WorkerServiceabilityAssemblyConfig.fromKernelConfigJson("{}");
    }

    private record Fixture(
            KernelPacerAssembly assembly,
            PythonKernelPacerProcess python,
            ResultRoutingApplication resultRouting,
            WorkerServiceabilityResultApplication serviceabilityResult,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            ResultRoutingApplicationConfig resultConfig,
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
    }
}
