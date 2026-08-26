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

import com.xa.mass.kernel.assembly.KernelPacerPolicyConfig;
import com.xa.mass.kernel.assignment.AssignmentDispatchApplication;
import com.xa.mass.kernel.assignment.AssignmentDispatchApplicationConfig;
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
    void disabledAssemblyStartsNothing() {
        Fixture fixture = fixture(false, disabledServiceability());

        fixture.assembly.start();

        assertThat(fixture.assembly.snapshot()).isEqualTo(
                new KernelPacerAssembly.Snapshot(
                        false,
                        KernelPacerAssembly.State.STOPPED,
                        "STOPPED",
                        "DISABLED",
                        "DISABLED",
                        "STOPPED"
                )
        );
        verify(fixture.resultRouting, never()).start(any());
        verify(fixture.serviceabilityResult, never())
                .start(any(), any(Long.class));
        verify(fixture.serviceabilityDispatch, never())
                .start(any(), any(Long.class));
        verify(fixture.assignmentDispatch, never()).start(any());
    }

    @Test
    void startsFourJavaStagesAndStopsInStrictReverseOrder() {
        Fixture fixture = fixture(true, enabledServiceability());
        when(fixture.resultRouting.isRunning()).thenReturn(true);
        when(fixture.resultRouting.state()).thenReturn("RUNNING");
        when(fixture.serviceabilityResult.isRunning()).thenReturn(true);
        when(fixture.serviceabilityResult.state()).thenReturn("RUNNING");
        when(fixture.serviceabilityDispatch.isRunning()).thenReturn(true);
        when(fixture.serviceabilityDispatch.state()).thenReturn("RUNNING");
        when(fixture.assignmentDispatch.isRunning()).thenReturn(true);
        when(fixture.assignmentDispatch.state()).thenReturn("RUNNING");

        fixture.assembly.start();
        assertThat(fixture.assembly.isRunning()).isTrue();
        assertThat(new KernelPacerHealthIndicator(fixture.assembly)
                .health().getStatus().getCode()).isEqualTo("UP");
        fixture.assembly.stop();

        InOrder order = inOrder(
                fixture.resultRouting,
                fixture.serviceabilityResult,
                fixture.serviceabilityDispatch,
                fixture.assignmentDispatch
        );
        order.verify(fixture.resultRouting).start(
                fixture.policy.resultRouting()
        );
        order.verify(fixture.serviceabilityResult).start(
                fixture.serviceability.result(),
                1_000L
        );
        order.verify(fixture.serviceabilityDispatch).start(
                fixture.serviceability.dispatch(),
                1_000L
        );
        order.verify(fixture.assignmentDispatch).start(
                fixture.policy.assignmentDispatch()
        );
        order.verify(fixture.assignmentDispatch).stop(any(Long.class));
        order.verify(fixture.serviceabilityDispatch).stop(any(Long.class));
        order.verify(fixture.serviceabilityResult).stop(any(Long.class));
        order.verify(fixture.resultRouting).stop(any(Long.class));
    }

    @Test
    void assignmentStartFailureRollsBackEarlierApplications() {
        Fixture fixture = fixture(true, enabledServiceability());
        doThrow(new IllegalStateException("assignment failed"))
                .when(fixture.assignmentDispatch)
                .start(fixture.policy.assignmentDispatch());

        assertThatThrownBy(fixture.assembly::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("assignment failed");

        InOrder order = inOrder(
                fixture.serviceabilityDispatch,
                fixture.serviceabilityResult,
                fixture.resultRouting
        );
        order.verify(fixture.serviceabilityDispatch).stop(any(Long.class));
        order.verify(fixture.serviceabilityResult).stop(any(Long.class));
        order.verify(fixture.resultRouting).stop(any(Long.class));
        assertThat(fixture.assembly.snapshot().state())
                .isEqualTo(KernelPacerAssembly.State.FAILED);
    }

    @Test
    void unexpectedAssignmentExitMakesReadinessDown() {
        Fixture fixture = fixture(true, enabledServiceability());
        when(fixture.resultRouting.isRunning()).thenReturn(true);
        when(fixture.serviceabilityResult.isRunning()).thenReturn(true);
        when(fixture.serviceabilityDispatch.isRunning()).thenReturn(true);
        when(fixture.assignmentDispatch.isRunning()).thenReturn(true, false);
        when(fixture.assignmentDispatch.state()).thenReturn("FAILED");
        fixture.assembly.start();

        assertThat(fixture.assembly.isRunning()).isTrue();
        assertThat(fixture.assembly.isRunning()).isFalse();
        assertThat(fixture.assembly.snapshot().assignmentDispatchState())
                .isEqualTo("FAILED");
        assertThat(new KernelPacerHealthIndicator(fixture.assembly)
                .health().getStatus().getCode()).isEqualTo("DOWN");

        fixture.assembly.destroy();
        verify(fixture.assignmentDispatch).stop(any(Long.class));
    }

    @Test
    void serviceabilityAbsentStartsResultAndAssignmentOnly() {
        Fixture fixture = fixture(true, disabledServiceability());
        when(fixture.resultRouting.isRunning()).thenReturn(true);
        when(fixture.assignmentDispatch.isRunning()).thenReturn(true);
        fixture.assembly.start();

        verify(fixture.serviceabilityResult, never())
                .start(any(), any(Long.class));
        verify(fixture.serviceabilityDispatch, never())
                .start(any(), any(Long.class));
        verify(fixture.assignmentDispatch).start(
                fixture.policy.assignmentDispatch()
        );
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
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KernelPacerProperties(
                true,
                "kernel.json",
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(
            boolean enabled,
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
        KernelPacerProperties properties = new KernelPacerProperties(
                enabled,
                "kernel.json",
                Duration.ofSeconds(1)
        );
        ResultRoutingApplication resultRouting = mock(
                ResultRoutingApplication.class
        );
        WorkerServiceabilityResultApplication result = mock(
                WorkerServiceabilityResultApplication.class
        );
        WorkerServiceabilityDispatchApplication dispatch = mock(
                WorkerServiceabilityDispatchApplication.class
        );
        AssignmentDispatchApplication assignment = mock(
                AssignmentDispatchApplication.class
        );
        when(resultRouting.state()).thenReturn("STOPPED");
        when(result.state()).thenReturn("STOPPED");
        when(dispatch.state()).thenReturn("STOPPED");
        when(assignment.state()).thenReturn("STOPPED");
        KernelPacerPolicyConfig policy = new KernelPacerPolicyConfig(
                new ResultRoutingApplicationConfig(100),
                serviceability,
                AssignmentDispatchApplicationConfig.defaults()
        );
        KernelPacerAssembly assembly = new KernelPacerAssembly(
                properties,
                policy,
                resultRouting,
                result,
                dispatch,
                assignment
        );
        return new Fixture(
                assembly,
                resultRouting,
                result,
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
                WorkerServiceabilityResultApplicationConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    private static WorkerServiceabilityAssemblyConfig disabledServiceability() {
        return new WorkerServiceabilityAssemblyConfig(
                false,
                0,
                WorkerServiceabilityResultApplicationConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    private record Fixture(
            KernelPacerAssembly assembly,
            ResultRoutingApplication resultRouting,
            WorkerServiceabilityResultApplication serviceabilityResult,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            AssignmentDispatchApplication assignmentDispatch,
            KernelPacerPolicyConfig policy,
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
    }
}
