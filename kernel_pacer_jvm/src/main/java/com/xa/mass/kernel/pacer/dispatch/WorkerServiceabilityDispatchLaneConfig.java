package com.xa.mass.kernel.pacer.dispatch;

record WorkerServiceabilityDispatchLaneConfig(
        long intervalMillis,
        WorkerServiceabilityDispatchConfig dispatch
) {

    static final long DEFAULT_INTERVAL_MILLIS = 1_000;

    WorkerServiceabilityDispatchLaneConfig {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive"
            );
        }
        java.util.Objects.requireNonNull(dispatch, "dispatch");
    }

    static WorkerServiceabilityDispatchLaneConfig defaults() {
        return new WorkerServiceabilityDispatchLaneConfig(
                DEFAULT_INTERVAL_MILLIS,
                WorkerServiceabilityDispatchConfig.defaults()
        );
    }
}
