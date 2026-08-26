package com.xa.mass.kernel.assignment;

public record TaskWorkerAllocationConfig(
        int taskBatchLimit,
        long workerLeaseDurationMillis
) {
    public TaskWorkerAllocationConfig {
        if (taskBatchLimit <= 0 || workerLeaseDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "allocation config values must be positive"
            );
        }
    }
}
