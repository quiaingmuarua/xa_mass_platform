package com.xa.mass.kernel.pacer;

record TaskRunningActivationConfig(
        long priorityRecheckStepMillis,
        int runningTaskSoftLimit
) {
    public TaskRunningActivationConfig {
        if (priorityRecheckStepMillis <= 0
                || runningTaskSoftLimit <= 0) {
            throw new IllegalArgumentException(
                    "activation config values must be positive"
            );
        }
    }
}
