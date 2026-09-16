package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
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
    void serviceabilityPresetSamplesOneMillisecondFloorPerAssembly() {
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
                    12_345,
                    config.hotEligibilityFloorMillis()
            );
        }
    }

    @Test
    void validatesPolicyFloorsWithoutEncodingConstraints() {
        assertEquals(1, new KernelPacerPolicyConfig(PolicyPreset.SERVICEABILITY_DEFAULT, 1).hotEligibilityFloorMillis());
        assertEquals(Long.MAX_VALUE, new KernelPacerPolicyConfig(PolicyPreset.SERVICEABILITY_DEFAULT,
                Long.MAX_VALUE).hotEligibilityFloorMillis());
        assertThrows(
                IllegalArgumentException.class,
                () -> new KernelPacerPolicyConfig(
                        PolicyPreset.SERVICEABILITY_DEFAULT,
                        0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new KernelPacerPolicyConfig(
                        PolicyPreset.DEFAULT,
                        1
                )
        );
    }
}
