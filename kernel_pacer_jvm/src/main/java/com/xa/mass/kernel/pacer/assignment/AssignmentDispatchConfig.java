package com.xa.mass.kernel.pacer;

record AssignmentDispatchConfig(
        long workerAllocationIntervalMillis,
        long runningActivationIntervalMillis,
        long taskDispatchIntervalMillis,
        TaskWorkerAllocationConfig workerAllocation,
        TaskRunningActivationConfig runningActivation,
        TaskDispatchConfig taskDispatch
) {
    static final long DEFAULT_INTERVAL_MILLIS = 100;
    static final int TASK_BATCH_LIMIT = 100;
    static final int WORKER_SCAN_LIMIT = 100;
    static final long WORKER_LEASE_DURATION_MILLIS = 5_000;
    static final int PER_TASK_DISPATCH_LIMIT = 100;
    static final long ITEM_CLAIM_LEASE_MILLIS = 5_000;
    static final long PRIORITY_RECHECK_STEP_MILLIS = 1_000;
    static final int DEFAULT_RUNNING_TASK_SOFT_LIMIT = 100;

    AssignmentDispatchConfig {
        if (workerAllocationIntervalMillis <= 0
                || runningActivationIntervalMillis <= 0
                || taskDispatchIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "Assignment Dispatch intervals must be positive"
            );
        }
        java.util.Objects.requireNonNull(
                workerAllocation,
                "workerAllocation"
        );
        java.util.Objects.requireNonNull(
                runningActivation,
                "runningActivation"
        );
        java.util.Objects.requireNonNull(taskDispatch, "taskDispatch");
    }

    static AssignmentDispatchConfig defaults() {
        return create(
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_RUNNING_TASK_SOFT_LIMIT
        );
    }

    static AssignmentDispatchConfig create(
            long workerAllocationIntervalMillis,
            long runningActivationIntervalMillis,
            long taskDispatchIntervalMillis,
            int runningTaskSoftLimit
    ) {
        return new AssignmentDispatchConfig(
                workerAllocationIntervalMillis,
                runningActivationIntervalMillis,
                taskDispatchIntervalMillis,
                new TaskWorkerAllocationConfig(
                        WORKER_LEASE_DURATION_MILLIS
                ),
                new TaskRunningActivationConfig(
                        PRIORITY_RECHECK_STEP_MILLIS,
                        runningTaskSoftLimit
                ),
                new TaskDispatchConfig(
                        PER_TASK_DISPATCH_LIMIT,
                        ITEM_CLAIM_LEASE_MILLIS
                )
        );
    }
}
