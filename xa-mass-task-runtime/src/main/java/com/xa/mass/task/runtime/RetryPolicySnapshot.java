package com.xa.mass.task.runtime;

public record RetryPolicySnapshot(
        RetryMode retryMode,
        int maxRetryCount,
        long retryDelayMillis,
        long retryPolicyVersion
) {

    public RetryPolicySnapshot {
        retryMode = retryMode == null ? RetryMode.FAST_READY : retryMode;
        maxRetryCount = Math.max(0, maxRetryCount);
        retryDelayMillis = Math.max(0L, retryDelayMillis);
        retryPolicyVersion = Math.max(0L, retryPolicyVersion);
    }
}
