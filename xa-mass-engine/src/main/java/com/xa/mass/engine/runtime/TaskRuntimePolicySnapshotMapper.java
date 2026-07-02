package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ResultFinalityPolicySnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RetryPolicySnapshot;

public final class TaskRuntimePolicySnapshotMapper {

    private TaskRuntimePolicySnapshotMapper() {
    }

    public static ClaimLeasePolicy toClaimLeasePolicy(Task task,
                                                      ResolvedTaskSchedulingPolicy policy,
                                                      int workerReservationCount,
                                                      long defaultLeaseSeconds) {
        ResolvedTaskSchedulingPolicy resolvedPolicy = resolvePolicy(task, policy);
        var claimPolicy = resolvedPolicy.claimPolicy();
        int batchSize = Math.max(1, resolvedPolicy.batchSize());
        int perWorkerCapacity = switch (claimPolicy.batchPolicy()) {
            case SMALL -> Math.min(batchSize, claimPolicy.smallPerWorkerCapacityLimit());
            case LARGE -> batchSize;
        };
        long leaseSeconds = switch (claimPolicy.leaseProfile()) {
            case SHORT -> Math.max(1L, Math.min(Math.max(1L, defaultLeaseSeconds), claimPolicy.shortLeaseSeconds()));
            case NORMAL -> Math.max(1L, defaultLeaseSeconds);
        };
        int maxItems = Math.max(1, perWorkerCapacity * Math.max(1, workerReservationCount));
        return new ClaimLeasePolicy(
                maxItems,
                leaseSeconds * 1_000L);
    }

    public static RetryPolicySnapshot toRetryPolicySnapshot(ResolvedTaskSchedulingPolicy policy,
                                                            int maxRetryCount,
                                                            long retryPolicyVersion) {
        ResolvedTaskSchedulingPolicy resolvedPolicy = resolvePolicy(null, policy);
        var retryPolicy = resolvedPolicy.retryPolicy();
        long retryDelayMillis = retryPolicy.workloadClass() == TaskWorkloadClass.INTERACTIVE
                ? retryPolicy.interactiveWorkRetryDelayMillis()
                : retryPolicy.bulkWorkRetryDelayMillis();
        return new RetryPolicySnapshot(
                retryDelayMillis > 0L ? RetryMode.DUE_TIME : RetryMode.FAST_READY,
                maxRetryCount >= 0 ? maxRetryCount : resolvedPolicy.defaultMaxRetryCount(),
                retryDelayMillis,
                retryPolicyVersion);
    }

    public static ResultFinalityPolicySnapshot toResultFinalityPolicySnapshot(ResolvedTaskSchedulingPolicy policy,
                                                                              long finalResultRetentionMillis) {
        ResolvedTaskSchedulingPolicy resolvedPolicy = resolvePolicy(null, policy);
        var finalityPolicy = resolvedPolicy.resultFinalityPolicy();
        return new ResultFinalityPolicySnapshot(
                finalityPolicy.retryExpiredLeaseFromAnyActiveState(),
                finalityPolicy.expiredLeaseFinalizesAsFailure(),
                finalResultRetentionMillis);
    }

    private static ResolvedTaskSchedulingPolicy resolvePolicy(Task task, ResolvedTaskSchedulingPolicy policy) {
        if (policy != null) {
            return policy;
        }
        return ResolvedTaskSchedulingPolicy.from(task, null);
    }
}
