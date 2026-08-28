package com.xa.mass.kernel.pacer.dispatch;

record TaskWorkerAllocationConfig(
        long workerLeaseDurationMillis
) {
    public TaskWorkerAllocationConfig {
        if (workerLeaseDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "allocation config values must be positive"
            );
        }
    }
}
