package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskItemResultEvents;
import com.xa.mass.kernel.worker.WorkerExecutionResultEvents;
import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import com.xa.mass.kernel.worker.WorkerServiceabilityEvents.NetworkObservation;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ResultConvergenceRuntimeTest {

    @ParameterizedTest
    @EnumSource(PolicyPreset.class)
    void everyPresetConsumesNetworkEvidence(PolicyPreset preset) {
        var handoff = mock(WorkerServiceabilityRuntime.class);
        var events = mock(WorkerServiceabilityEvents.class);
        long now = System.currentTimeMillis();
        var report = DeliveryReport.create(
                DeliveryEndpoint.SERVER,
                "system-polling",
                DeliveryEndpoint.KERNEL,
                "platform.server.worker-poll.observed",
                "",
                "{\"workerId\":\"worker-1\",\"observedAtMillis\":" + now + "}",
                "worker-serviceability-evidence:v1"
        );
        when(handoff.consumeNetworkEvidenceResults(anyInt()))
                .thenReturn(List.of(report)).thenReturn(List.of());
        var runtime = ResultConvergenceRuntime.assemble(preset,
                mock(TaskResultRuntime.class), mock(TaskItemResultEvents.class),
                mock(WorkerExecutionResultEvents.class), events, handoff);
        try {
            runtime.start();
            verify(events, timeout(2000)).onAvailable(Map.of(
                    "worker-1", new NetworkObservation("system-polling", now)));
        } finally {
            runtime.stop(2000);
        }
    }

    @Test
    void keepsFiniteResultPresetValuesInsideTheResultPackage() {
        assertConfig(PolicyPreset.DEFAULT, 100, 100, 10);
        assertConfig(PolicyPreset.SERVICEABILITY_DEFAULT, 100, 100, 10);
        assertConfig(PolicyPreset.SCENARIO_LAB, 20, 100, 10);
        assertConfig(PolicyPreset.RUNTIME_BOUNDARY_PROOF, 100, 20, 100);
    }

    private static void assertConfig(
            PolicyPreset preset,
            long taskInterval,
            long evidenceInterval,
            int evidenceLimit
    ) {
        ResultConvergenceConfig convergence =
                ResultConvergenceRuntime.configForPreset(preset);
        WorkerServiceabilityResultConfig serviceability =
                ResultConvergenceRuntime.serviceabilityConfigForPreset(
                        preset
                );

        assertEquals(
                taskInterval,
                convergence.taskResultIdleIntervalMillis()
        );
        assertEquals(
                evidenceInterval,
                convergence.networkEvidenceIdleIntervalMillis()
        );
        assertEquals(evidenceLimit, serviceability.resultReportLimit());
        assertEquals(30_000, serviceability.evidenceMaxAgeMillis());
    }
}
