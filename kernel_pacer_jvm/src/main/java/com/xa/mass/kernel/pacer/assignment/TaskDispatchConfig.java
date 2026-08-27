package com.xa.mass.kernel.pacer;

record TaskDispatchConfig(
        int perTaskDispatchLimit,
        long itemClaimLeaseDurationMillis
) {
    public TaskDispatchConfig {
        if (perTaskDispatchLimit <= 0
                || itemClaimLeaseDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "dispatch config values must be positive"
            );
        }
    }
}
