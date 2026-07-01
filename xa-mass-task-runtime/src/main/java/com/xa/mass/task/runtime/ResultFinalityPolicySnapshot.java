package com.xa.mass.task.runtime;

public record ResultFinalityPolicySnapshot(
        boolean retryExpiredLeaseFromAnyActiveState,
        boolean expiredLeaseFinalizesAsFailure,
        long finalResultRetentionMillis
) {

    public ResultFinalityPolicySnapshot {
        finalResultRetentionMillis = Math.max(0L, finalResultRetentionMillis);
    }
}
