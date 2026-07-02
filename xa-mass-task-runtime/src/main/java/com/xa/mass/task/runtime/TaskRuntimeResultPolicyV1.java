package com.xa.mass.task.runtime;

public record TaskRuntimeResultPolicyV1(
        RetryMode retryMode,
        int maxRetryCount,
        long retryDelayMillis,
        long retryPolicyVersion,
        boolean retryExpiredLeaseFromAnyActiveState,
        boolean expiredLeaseFinalizesAsFailure,
        long finalResultRetentionMillis
) {

    private static final long DEFAULT_FINAL_RESULT_RETENTION_MILLIS = 86_400_000L;

    public TaskRuntimeResultPolicyV1 {
        retryMode = retryMode == null ? RetryMode.FAST_READY : retryMode;
        maxRetryCount = Math.max(0, maxRetryCount);
        retryDelayMillis = Math.max(0L, retryDelayMillis);
        retryPolicyVersion = Math.max(0L, retryPolicyVersion);
        finalResultRetentionMillis = Math.max(0L, finalResultRetentionMillis);
    }

    public static TaskRuntimeResultPolicyV1 defaultPolicy() {
        return new TaskRuntimeResultPolicyV1(
                RetryMode.FAST_READY,
                0,
                0L,
                0L,
                false,
                true,
                DEFAULT_FINAL_RESULT_RETENTION_MILLIS);
    }

    public static TaskRuntimeResultPolicyV1 from(RetryPolicySnapshot retryPolicy,
                                                 ResultFinalityPolicySnapshot finalityPolicy) {
        RetryPolicySnapshot retry = retryPolicy == null
                ? new RetryPolicySnapshot(RetryMode.FAST_READY, 0, 0L, 0L)
                : retryPolicy;
        ResultFinalityPolicySnapshot finality = finalityPolicy == null
                ? new ResultFinalityPolicySnapshot(false, true, DEFAULT_FINAL_RESULT_RETENTION_MILLIS)
                : finalityPolicy;
        return new TaskRuntimeResultPolicyV1(
                retry.retryMode(),
                retry.maxRetryCount(),
                retry.retryDelayMillis(),
                retry.retryPolicyVersion(),
                finality.retryExpiredLeaseFromAnyActiveState(),
                finality.expiredLeaseFinalizesAsFailure(),
                finality.finalResultRetentionMillis());
    }

    public RetryPolicySnapshot toRetryPolicySnapshot() {
        return new RetryPolicySnapshot(retryMode, maxRetryCount, retryDelayMillis, retryPolicyVersion);
    }

    public ResultFinalityPolicySnapshot toFinalityPolicySnapshot() {
        return new ResultFinalityPolicySnapshot(
                retryExpiredLeaseFromAnyActiveState,
                expiredLeaseFinalizesAsFailure,
                finalResultRetentionMillis);
    }
}
