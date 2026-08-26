package com.xa.mass.kernel.pacer;

record AssignmentDispatchApplicationConfig(
        long workerAllocationIntervalMillis,
        long runningActivationIntervalMillis,
        long taskDispatchIntervalMillis,
        TaskWorkerAllocationConfig workerAllocation,
        TaskRunningActivationConfig runningActivation,
        TaskDispatchConfig taskDispatch
) {
    public static final long DEFAULT_INTERVAL_MILLIS = 100;
    public static final int TASK_BATCH_LIMIT = 100;
    public static final int WORKER_SCAN_LIMIT = 100;
    public static final long WORKER_LEASE_DURATION_MILLIS = 5_000;
    public static final int PER_TASK_DISPATCH_LIMIT = 100;
    public static final long ITEM_CLAIM_LEASE_MILLIS = 5_000;
    public static final long PRIORITY_RECHECK_STEP_MILLIS = 1_000;
    public static final int DEFAULT_RUNNING_TASK_SOFT_LIMIT = 100;

    public AssignmentDispatchApplicationConfig {
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

    public static AssignmentDispatchApplicationConfig defaults() {
        return create(
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_INTERVAL_MILLIS,
                DEFAULT_RUNNING_TASK_SOFT_LIMIT
        );
    }

    public static AssignmentDispatchApplicationConfig create(
            long workerAllocationIntervalMillis,
            long runningActivationIntervalMillis,
            long taskDispatchIntervalMillis,
            int runningTaskSoftLimit
    ) {
        return new AssignmentDispatchApplicationConfig(
                workerAllocationIntervalMillis,
                runningActivationIntervalMillis,
                taskDispatchIntervalMillis,
                new TaskWorkerAllocationConfig(
                        TASK_BATCH_LIMIT,
                        WORKER_LEASE_DURATION_MILLIS
                ),
                new TaskRunningActivationConfig(
                        TASK_BATCH_LIMIT,
                        PRIORITY_RECHECK_STEP_MILLIS,
                        runningTaskSoftLimit
                ),
                new TaskDispatchConfig(
                        TASK_BATCH_LIMIT,
                        PER_TASK_DISPATCH_LIMIT,
                        ITEM_CLAIM_LEASE_MILLIS
                )
        );
    }
}
