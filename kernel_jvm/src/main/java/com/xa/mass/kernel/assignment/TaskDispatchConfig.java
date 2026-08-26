package com.xa.mass.kernel.assignment;

public record TaskDispatchConfig(
        int taskBatchLimit,
        int perTaskDispatchLimit,
        long itemClaimLeaseDurationMillis
) {
    public TaskDispatchConfig {
        if (taskBatchLimit <= 0
                || perTaskDispatchLimit <= 0
                || itemClaimLeaseDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "dispatch config values must be positive"
            );
        }
    }
}
