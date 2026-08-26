package com.xa.mass.kernel.assignment;

public record TaskRunningActivationConfig(
        int taskBatchLimit,
        long priorityRecheckStepMillis,
        int runningTaskSoftLimit
) {
    public TaskRunningActivationConfig {
        if (taskBatchLimit <= 0
                || priorityRecheckStepMillis <= 0
                || runningTaskSoftLimit <= 0) {
            throw new IllegalArgumentException(
                    "activation config values must be positive"
            );
        }
    }
}
