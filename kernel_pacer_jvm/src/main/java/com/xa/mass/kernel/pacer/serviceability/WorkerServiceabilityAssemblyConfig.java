package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.Objects;

record WorkerServiceabilityAssemblyConfig(
        boolean enabled,
        long hotEligibilityFloorMillis,
        WorkerServiceabilityResultConfig result,
        WorkerServiceabilityDispatchApplicationConfig dispatch
) {

    public WorkerServiceabilityAssemblyConfig {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(dispatch, "dispatch");
        if (enabled) {
            requireFloor(hotEligibilityFloorMillis);
        } else if (hotEligibilityFloorMillis != 0) {
            throw new IllegalArgumentException(
                    "disabled Serviceability must not carry a HOT floor"
            );
        }
    }

    static WorkerServiceabilityAssemblyConfig disabled() {
        return new WorkerServiceabilityAssemblyConfig(
                false,
                0,
                WorkerServiceabilityResultConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    static void requireFloor(long floor) {
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
