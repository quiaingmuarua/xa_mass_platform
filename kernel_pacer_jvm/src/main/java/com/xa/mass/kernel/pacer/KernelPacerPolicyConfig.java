package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Root policy facts shared by both convergence packages.
 *
 * <p>Each package owns its concrete preset values. The root owns only the
 * selected finite preset and one activation floor. Network evidence uses it in
 * every preset; only scanning presets apply it to Serviceability and Refill.</p>
 */
record KernelPacerPolicyConfig(
        PolicyPreset preset,
        long activationFloorMillis
) {

    KernelPacerPolicyConfig {
        Objects.requireNonNull(preset, "preset");
        requireFloor(activationFloorMillis);
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
        long floor = currentTimeMillis.getAsLong();
        return new KernelPacerPolicyConfig(preset, floor);
    }

    boolean serviceabilityEnabled() {
        return serviceabilityEnabled(preset);
    }

    long hotEligibilityFloorMillis() {
        return serviceabilityEnabled() ? activationFloorMillis : 0;
    }

    private static boolean serviceabilityEnabled(PolicyPreset preset) {
        return preset != PolicyPreset.DEFAULT;
    }

    private static void requireFloor(long floor) {
        if (floor <= 0) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be positive"
            );
        }
    }
}
