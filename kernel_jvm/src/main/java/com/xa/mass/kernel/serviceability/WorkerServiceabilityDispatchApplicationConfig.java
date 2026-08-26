package com.xa.mass.kernel.serviceability;

public record WorkerServiceabilityDispatchApplicationConfig(
        long intervalMillis,
        WorkerServiceabilityDispatchConfig dispatch
) {

    public static final long DEFAULT_INTERVAL_MILLIS = 1_000;

    public WorkerServiceabilityDispatchApplicationConfig {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive"
            );
        }
        java.util.Objects.requireNonNull(dispatch, "dispatch");
    }

    public static WorkerServiceabilityDispatchApplicationConfig defaults() {
        return new WorkerServiceabilityDispatchApplicationConfig(
                DEFAULT_INTERVAL_MILLIS,
                WorkerServiceabilityDispatchConfig.defaults()
        );
    }
}
