package com.xa.mass.kernel.pacer.dispatch;

record AssignmentDispatchConfig(
        long workerAllocationIntervalMillis,
        long taskInitializationIntervalMillis,
        long taskDispatchIntervalMillis
) {
    static final long DEFAULT_INTERVAL_MILLIS = 100;

    AssignmentDispatchConfig {
        if (workerAllocationIntervalMillis <= 0
                || taskInitializationIntervalMillis <= 0
                || taskDispatchIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "Assignment Dispatch intervals must be positive"
            );
        }
    }

    static AssignmentDispatchConfig defaults() {
        return create(
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_INTERVAL_MILLIS
        );
    }

    static AssignmentDispatchConfig create(
            long workerAllocationIntervalMillis,
            long taskInitializationIntervalMillis,
            long taskDispatchIntervalMillis
    ) {
        return new AssignmentDispatchConfig(
                workerAllocationIntervalMillis,
                taskInitializationIntervalMillis,
                taskDispatchIntervalMillis
        );
    }
}
