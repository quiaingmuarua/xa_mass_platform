package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.Objects;

record WorkerServiceabilityDispatchAssemblyConfig(
        boolean enabled,
        long hotEligibilityFloorMillis,
        long intervalMillis,
        WorkerServiceabilityDispatchConfig dispatch
) {

    static final long DEFAULT_INTERVAL_MILLIS = 1_000;

    WorkerServiceabilityDispatchAssemblyConfig {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive"
            );
        }
        Objects.requireNonNull(dispatch, "dispatch");
        if (enabled) {
            requireFloor(hotEligibilityFloorMillis);
        } else if (hotEligibilityFloorMillis != 0) {
            throw new IllegalArgumentException(
                    "disabled Serviceability must not carry a HOT floor"
            );
        }
    }

    static WorkerServiceabilityDispatchAssemblyConfig disabled() {
        return new WorkerServiceabilityDispatchAssemblyConfig(
                false,
                0,
                DEFAULT_INTERVAL_MILLIS,
                WorkerServiceabilityDispatchConfig.defaults()
        );
    }

    static void requireFloor(long floor) {
        if (floor < WorkerScoreCore.SLOT_MILLIS
                || floor % WorkerScoreCore.SLOT_MILLIS
                != 0
                || floor > WorkerScoreCore.MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be a valid "
                            + "score-slot-aligned time"
            );
        }
    }
}
