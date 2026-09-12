package com.xa.mass.kernel.pacer.dispatch;

record AssignmentDispatchConfig(
        long taskInitializationIntervalMillis,
        long taskDispatchIntervalMillis
) {
    static final long DEFAULT_INTERVAL_MILLIS = 100;
    static final long DEFAULT_TASK_DISPATCH_INTERVAL_MILLIS = 50;

    AssignmentDispatchConfig {
        if (taskInitializationIntervalMillis <= 0
                || taskDispatchIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "Assignment Dispatch intervals must be positive"
            );
        }
    }

    static AssignmentDispatchConfig defaults() {
        return create(
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_TASK_DISPATCH_INTERVAL_MILLIS
        );
    }

    static AssignmentDispatchConfig create(
            long taskInitializationIntervalMillis,
            long taskDispatchIntervalMillis
    ) {
        return new AssignmentDispatchConfig(
                taskInitializationIntervalMillis,
                taskDispatchIntervalMillis
        );
    }
}
