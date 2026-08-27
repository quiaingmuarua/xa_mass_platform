package com.xa.mass.kernel.pacer;

record AssignmentDispatchConfig(
        long workerAllocationIntervalMillis,
        long taskInitializationIntervalMillis,
        long taskDispatchIntervalMillis,
        TaskWorkerAllocationConfig workerAllocation,
        TaskDispatchConfig taskDispatch
) {
    static final long DEFAULT_INTERVAL_MILLIS = 100;
    static final int TASK_BATCH_LIMIT = 100;
    static final int WORKER_SCAN_LIMIT = 100;
    static final long WORKER_LEASE_DURATION_MILLIS = 5_000;
    static final int PER_TASK_DISPATCH_LIMIT = 100;
    static final long ITEM_CLAIM_LEASE_MILLIS = 5_000;

    AssignmentDispatchConfig {
        if (workerAllocationIntervalMillis <= 0
                || taskInitializationIntervalMillis <= 0
                || taskDispatchIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "Assignment Dispatch intervals must be positive"
            );
        }
        java.util.Objects.requireNonNull(
                workerAllocation,
                "workerAllocation"
        );
        java.util.Objects.requireNonNull(taskDispatch, "taskDispatch");
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
                taskDispatchIntervalMillis,
                new TaskWorkerAllocationConfig(
                        WORKER_LEASE_DURATION_MILLIS
                ),
                new TaskDispatchConfig(
                        PER_TASK_DISPATCH_LIMIT,
                        ITEM_CLAIM_LEASE_MILLIS
                )
        );
    }
}
