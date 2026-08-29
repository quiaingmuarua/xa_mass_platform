package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Root policy facts shared by both convergence packages.
 *
 * <p>Each package owns its concrete preset values. The root owns only the
 * selected finite preset and the single HOT eligibility floor shared by
 * Serviceability Dispatch and Assignment.</p>
 */
record KernelPacerPolicyConfig(
        PolicyPreset preset,
        long hotEligibilityFloorMillis
) {

    KernelPacerPolicyConfig {
        Objects.requireNonNull(preset, "preset");
        if (serviceabilityEnabled(preset)) {
            requireFloor(hotEligibilityFloorMillis);
        } else if (hotEligibilityFloorMillis != 0) {
            throw new IllegalArgumentException(
                    "disabled Serviceability must not carry a HOT floor"
            );
        }
    }

    static KernelPacerPolicyConfig forPreset(PolicyPreset preset) {
        return forPreset(preset, System::currentTimeMillis);
    }

    static KernelPacerPolicyConfig forPreset(
            PolicyPreset preset,
            LongSupplier currentTimeMillis
    ) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        if (!serviceabilityEnabled(preset)) {
            return new KernelPacerPolicyConfig(preset, 0);
        }
        long current = currentTimeMillis.getAsLong();
        long floor = current / WorkerScoreCore.SLOT_MILLIS
                * WorkerScoreCore.SLOT_MILLIS;
        return new KernelPacerPolicyConfig(preset, floor);
    }

    boolean serviceabilityEnabled() {
        return serviceabilityEnabled(preset);
    }

    private static boolean serviceabilityEnabled(PolicyPreset preset) {
        return preset != PolicyPreset.DEFAULT;
    }

    private static void requireFloor(long floor) {
        if (floor < WorkerScoreCore.SLOT_MILLIS
                || floor % WorkerScoreCore.SLOT_MILLIS != 0
                || floor > WorkerScoreCore.MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be a valid "
                            + "score-slot-aligned time"
            );
        }
    }
}
