package com.xa.mass.kernel.pacer.dispatch;

import java.util.Objects;

record WorkerServiceabilityDispatchAssemblyConfig(
        boolean enabled,
        long hotEligibilityFloorMillis,
        WorkerServiceabilityDispatchLaneConfig lane
) {

    WorkerServiceabilityDispatchAssemblyConfig {
        Objects.requireNonNull(lane, "lane");
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
                WorkerServiceabilityDispatchLaneConfig.defaults()
        );
    }

    static void requireFloor(long floor) {
        if (floor < WorkerServiceabilityDispatchMechanism.SLOT_MILLIS
                || floor % WorkerServiceabilityDispatchMechanism.SLOT_MILLIS
                != 0
                || floor > WorkerServiceabilityDispatchMechanism
                .MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be a valid "
                            + "score-slot-aligned time"
            );
        }
    }
}
