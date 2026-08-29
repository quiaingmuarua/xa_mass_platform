package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import org.junit.jupiter.api.Test;

class ResultConvergenceRuntimeTest {

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
                convergence.adapterEvidenceIdleIntervalMillis()
        );
        assertEquals(evidenceLimit, serviceability.resultReportLimit());
        assertEquals(30_000, serviceability.evidenceMaxAgeMillis());
    }
}
