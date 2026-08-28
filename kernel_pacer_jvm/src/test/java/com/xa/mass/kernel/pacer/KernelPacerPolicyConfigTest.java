package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KernelPacerPolicyConfigTest {

    @Test
    void defaultPresetDoesNotMintAServiceabilityFloor() {
        AtomicInteger clockReads = new AtomicInteger();
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.forPreset(
                PolicyPreset.DEFAULT,
                clockReads::incrementAndGet
        );

        assertEquals(0, clockReads.get());
        assertEquals(PolicyPreset.DEFAULT, config.preset());
        assertFalse(config.serviceabilityEnabled());
        assertEquals(0, config.hotEligibilityFloorMillis());
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
            assertEquals(preset, config.preset());
            assertTrue(config.serviceabilityEnabled());
            assertEquals(
                    12_300,
                    config.hotEligibilityFloorMillis()
            );
        }
    }

    @Test
    void rejectsFloorsOutsideTheSharedScoreContract() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KernelPacerPolicyConfig(
                        PolicyPreset.SERVICEABILITY_DEFAULT,
                        WorkerScoreCore.SLOT_MILLIS - 1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new KernelPacerPolicyConfig(
                        PolicyPreset.DEFAULT,
                        WorkerScoreCore.SLOT_MILLIS
                )
        );
    }
}
