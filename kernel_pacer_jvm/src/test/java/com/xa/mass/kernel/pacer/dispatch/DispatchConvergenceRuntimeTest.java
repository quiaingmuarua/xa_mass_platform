package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchConvergenceRuntimeTest {

    @Test
    void keepsFiniteAssignmentPresetValuesInsideTheDispatchPackage() {
        assertAssignment(PolicyPreset.DEFAULT, 100);
        assertAssignment(PolicyPreset.SERVICEABILITY_DEFAULT, 100);
        assertAssignment(PolicyPreset.SCENARIO_LAB, 20);
        assertAssignment(PolicyPreset.RUNTIME_BOUNDARY_PROOF, 100);
    }

    @Test
    void keepsFiniteServiceabilityPresetValuesInsideTheDispatchPackage() {
        WorkerServiceabilityDispatchAssemblyConfig disabled =
                DispatchConvergenceRuntime.serviceabilityConfigForPreset(
                        PolicyPreset.DEFAULT,
                        0
                );
        assertFalse(disabled.enabled());

        assertServiceability(PolicyPreset.SERVICEABILITY_DEFAULT, 60_000);
        assertServiceability(PolicyPreset.SCENARIO_LAB, 60_000);
        assertServiceability(PolicyPreset.RUNTIME_BOUNDARY_PROOF, 10);
    }

    private static void assertAssignment(
            PolicyPreset preset,
            long expectedInterval
    ) {
        AssignmentDispatchConfig config =
                DispatchConvergenceRuntime.assignmentConfigForPreset(preset);
        assertEquals(
                expectedInterval,
                config.workerAllocationIntervalMillis()
        );
        assertEquals(
                expectedInterval,
                config.taskInitializationIntervalMillis()
        );
        assertEquals(
                expectedInterval,
                config.taskDispatchIntervalMillis()
        );
    }

    private static void assertServiceability(
            PolicyPreset preset,
            long expectedRecoveryInterval
    ) {
        WorkerServiceabilityDispatchAssemblyConfig config =
                DispatchConvergenceRuntime.serviceabilityConfigForPreset(
                        preset,
                        12_300
                );
        assertTrue(config.enabled());
        assertEquals(12_300, config.hotEligibilityFloorMillis());
        assertEquals(1_000, config.lane().intervalMillis());
        assertEquals(
                expectedRecoveryInterval,
                config.lane().dispatch().recoveryRetryIntervalMillis()
        );
        assertEquals(
                10_000,
                config.lane().dispatch().probeSweepRestartDelayMillis()
        );
        assertEquals(5, config.lane().dispatch().maxRecoveryAttempts());
        assertEquals(80, config.lane().dispatch().hotScanLimit());
        assertEquals(20, config.lane().dispatch().recoveryScanLimit());
        assertEquals(
                List.of("system-polling"),
                config.lane().dispatch()
                        .probeExcludedEndpointManagerIds()
        );
    }
}
