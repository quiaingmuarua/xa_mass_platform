package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KernelPacerPolicyConfigTest {

    @Test
    void defaultPresetKeepsProductionDefaultsWithoutServiceability() {
        AtomicInteger clockReads = new AtomicInteger();
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.forPreset(
                PolicyPreset.DEFAULT,
                clockReads::incrementAndGet
        );

        assertEquals(0, clockReads.get());
        assertEquals(
                100,
                config.resultConvergence().taskResultIdleIntervalMillis()
        );
        assertEquals(
                100,
                ResultConvergenceConfig.TASK_RESULT_BATCH_LIMIT
        );
        assertFalse(config.workerServiceability().enabled());
        assertEquals(0, config.workerServiceability()
                .hotEligibilityFloorMillis());
        assertAssignmentIntervals(config, 100);
    }

    @Test
    void serviceabilityDefaultPresetUsesProductionCadence() {
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.forPreset(
                PolicyPreset.SERVICEABILITY_DEFAULT,
                () -> 12_345L
        );

        assertEquals(
                100,
                config.resultConvergence().taskResultIdleIntervalMillis()
        );
        assertEquals(
                100,
                config.resultConvergence()
                        .adapterEvidenceIdleIntervalMillis()
        );
        assertAssignmentIntervals(config, 100);
        assertServiceability(
                config.workerServiceability(),
                1_000,
                60_000,
                10
        );
    }

    @Test
    void scenarioLabPresetKeepsTheCheckedLabPolicy() {
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.forPreset(
                PolicyPreset.SCENARIO_LAB,
                () -> 12_345L
        );

        assertEquals(
                20,
                config.resultConvergence().taskResultIdleIntervalMillis()
        );
        assertEquals(
                100,
                config.resultConvergence()
                        .adapterEvidenceIdleIntervalMillis()
        );
        assertAssignmentIntervals(config, 20);
        assertServiceability(
                config.workerServiceability(),
                1_000,
                60_000,
                10
        );
    }

    @Test
    void runtimeBoundaryPresetKeepsTheCheckedProofPolicy() {
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.forPreset(
                PolicyPreset.RUNTIME_BOUNDARY_PROOF,
                () -> 12_345L
        );

        assertEquals(
                100,
                config.resultConvergence().taskResultIdleIntervalMillis()
        );
        assertEquals(
                20,
                config.resultConvergence()
                        .adapterEvidenceIdleIntervalMillis()
        );
        assertAssignmentIntervals(config, 100);
        assertServiceability(
                config.workerServiceability(),
                1_000,
                10,
                100
        );
    }

    @Test
    void serviceabilityPresetMintsOneAlignedFloorPerAssembly() {
        for (PolicyPreset preset : List.of(
                PolicyPreset.SERVICEABILITY_DEFAULT,
                PolicyPreset.SCENARIO_LAB,
                PolicyPreset.RUNTIME_BOUNDARY_PROOF
        )) {
            AtomicInteger clockReads = new AtomicInteger();

            KernelPacerPolicyConfig config = KernelPacerPolicyConfig.forPreset(
                    preset,
                    () -> {
                        clockReads.incrementAndGet();
                        return 12_345L;
                    }
            );

            assertEquals(1, clockReads.get());
            assertEquals(
                    12_300,
                    config.workerServiceability()
                            .hotEligibilityFloorMillis()
            );
        }
    }

    private static void assertAssignmentIntervals(
            KernelPacerPolicyConfig config,
            long expected
    ) {
        assertEquals(expected, config.assignmentDispatch()
                .workerAllocationIntervalMillis());
        assertEquals(expected, config.assignmentDispatch()
                .runningActivationIntervalMillis());
        assertEquals(expected, config.assignmentDispatch()
                .taskDispatchIntervalMillis());
        assertEquals(100, config.assignmentDispatch().runningActivation()
                .runningTaskSoftLimit());
    }

    private static void assertServiceability(
            WorkerServiceabilityAssemblyConfig config,
            long dispatchInterval,
            long recoveryRetryInterval,
            int resultReportLimit
    ) {
        assertTrue(config.enabled());
        assertEquals(12_300, config.hotEligibilityFloorMillis());
        assertEquals(5, config.result().maxRecoveryAttempts());
        assertEquals(
                resultReportLimit,
                config.result().resultReportLimit()
        );
        assertEquals(
                30_000,
                config.result().evidenceMaxAgeMillis()
        );
        assertEquals(dispatchInterval, config.dispatch().intervalMillis());
        assertEquals(100, config.dispatch().dispatch().taskScanLimit());
        assertEquals(
                recoveryRetryInterval,
                config.dispatch().dispatch().recoveryRetryIntervalMillis()
        );
        assertEquals(
                10_000,
                config.dispatch().dispatch().probeSweepRestartDelayMillis()
        );
        assertEquals(5, config.dispatch().dispatch().maxRecoveryAttempts());
        assertEquals(80, config.dispatch().dispatch().hotScanLimit());
        assertEquals(20, config.dispatch().dispatch().recoveryScanLimit());
        assertEquals(
                List.of("system-polling"),
                config.dispatch().dispatch()
                        .probeExcludedEndpointManagerIds()
        );
    }
}
