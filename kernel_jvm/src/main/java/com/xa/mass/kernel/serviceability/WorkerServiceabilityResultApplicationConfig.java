package com.xa.mass.kernel.serviceability;

public record WorkerServiceabilityResultApplicationConfig(
        long intervalMillis,
        WorkerServiceabilityResultConfig result
) {

    public static final long DEFAULT_INTERVAL_MILLIS = 100;

    public WorkerServiceabilityResultApplicationConfig {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive"
            );
        }
        java.util.Objects.requireNonNull(result, "result");
    }

    public static WorkerServiceabilityResultApplicationConfig defaults() {
        return new WorkerServiceabilityResultApplicationConfig(
                DEFAULT_INTERVAL_MILLIS,
                WorkerServiceabilityResultConfig.defaults()
        );
    }
}
