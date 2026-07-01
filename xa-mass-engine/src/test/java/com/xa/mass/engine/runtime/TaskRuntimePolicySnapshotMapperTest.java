package com.xa.mass.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import org.junit.jupiter.api.Test;

class TaskRuntimePolicySnapshotMapperTest {

    @Test
    void mapsBulkClaimLeaseToTotalBatchCapacity() {
        var epoch = RuntimeEpoch.of("task-1", 9L);
        var policy = policy(
                TaskWorkloadClass.BULK,
                TaskRuntimeProfile.BatchPolicy.LARGE,
                TaskRuntimeProfile.LeaseProfile.NORMAL,
                TaskRuntimeProfile.BackpressureClass.BULK,
                4,
                3,
                -1,
                0L,
                0L,
                true,
                true);

        var snapshot = TaskRuntimePolicySnapshotMapper.toClaimLeasePolicy(
                null,
                policy,
                3,
                120L,
                7L,
                epoch);

        assertThat(snapshot.maxItems()).isEqualTo(12);
        assertThat(snapshot.leaseMillis()).isEqualTo(120_000L);
        assertThat(snapshot.attemptPolicyVersion()).isEqualTo(7L);
        assertThat(snapshot.expectedRuntimeEpoch()).isEqualTo(epoch);
    }

    @Test
    void mapsInteractiveClaimLeaseToSmallCapacityAndShortLease() {
        var policy = policy(
                TaskWorkloadClass.INTERACTIVE,
                TaskRuntimeProfile.BatchPolicy.SMALL,
                TaskRuntimeProfile.LeaseProfile.SHORT,
                TaskRuntimeProfile.BackpressureClass.INTERACTIVE,
                8,
                3,
                32,
                120L,
                0L,
                true,
                true);

        var snapshot = TaskRuntimePolicySnapshotMapper.toClaimLeasePolicy(
                null,
                policy,
                3,
                300L,
                1L,
                RuntimeEpoch.of("task-1", 1L));

        assertThat(snapshot.maxItems()).isEqualTo(6);
        assertThat(snapshot.leaseMillis()).isEqualTo(30_000L);
    }

    @Test
    void mapsAppendAdmissionToConfiguredBatchSizeAndReadyBacklog() {
        var policy = policy(
                TaskWorkloadClass.INTERACTIVE,
                TaskRuntimeProfile.BatchPolicy.SMALL,
                TaskRuntimeProfile.LeaseProfile.SHORT,
                TaskRuntimeProfile.BackpressureClass.INTERACTIVE,
                8,
                3,
                32,
                120L,
                0L,
                true,
                true);

        var snapshot = TaskRuntimePolicySnapshotMapper.toAppendAdmissionPolicy(policy, 500);

        assertThat(snapshot.maxAppendBatchSize()).isEqualTo(500);
        assertThat(snapshot.maxReadyBacklogItems()).isEqualTo(32);
    }

    @Test
    void mapsRetryPolicyToDueTimeOrFastReadySnapshot() {
        var interactive = policy(
                TaskWorkloadClass.INTERACTIVE,
                TaskRuntimeProfile.BatchPolicy.SMALL,
                TaskRuntimeProfile.LeaseProfile.SHORT,
                TaskRuntimeProfile.BackpressureClass.INTERACTIVE,
                8,
                4,
                32,
                120L,
                0L,
                true,
                true);
        var bulk = policy(
                TaskWorkloadClass.BULK,
                TaskRuntimeProfile.BatchPolicy.LARGE,
                TaskRuntimeProfile.LeaseProfile.NORMAL,
                TaskRuntimeProfile.BackpressureClass.BULK,
                4,
                3,
                -1,
                0L,
                0L,
                true,
                true);

        var interactiveSnapshot = TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(interactive, -1, 5L);
        var bulkSnapshot = TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(bulk, 9, 6L);

        assertThat(interactiveSnapshot.retryMode()).isEqualTo(RetryMode.DUE_TIME);
        assertThat(interactiveSnapshot.retryDelayMillis()).isEqualTo(120L);
        assertThat(interactiveSnapshot.maxRetryCount()).isEqualTo(4);
        assertThat(interactiveSnapshot.retryPolicyVersion()).isEqualTo(5L);
        assertThat(bulkSnapshot.retryMode()).isEqualTo(RetryMode.FAST_READY);
        assertThat(bulkSnapshot.retryDelayMillis()).isZero();
        assertThat(bulkSnapshot.maxRetryCount()).isEqualTo(9);
        assertThat(bulkSnapshot.retryPolicyVersion()).isEqualTo(6L);
    }

    @Test
    void mapsResultFinalityPolicySnapshot() {
        var policy = policy(
                TaskWorkloadClass.BULK,
                TaskRuntimeProfile.BatchPolicy.LARGE,
                TaskRuntimeProfile.LeaseProfile.NORMAL,
                TaskRuntimeProfile.BackpressureClass.BULK,
                4,
                3,
                -1,
                0L,
                0L,
                false,
                false);

        var snapshot = TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(policy, 86_400_000L);

        assertThat(snapshot.retryExpiredLeaseFromAnyActiveState()).isFalse();
        assertThat(snapshot.expiredLeaseFinalizesAsFailure()).isFalse();
        assertThat(snapshot.finalResultRetentionMillis()).isEqualTo(86_400_000L);
    }

    private static ResolvedTaskSchedulingPolicy policy(TaskWorkloadClass workloadClass,
                                                       TaskRuntimeProfile.BatchPolicy batchPolicy,
                                                       TaskRuntimeProfile.LeaseProfile leaseProfile,
                                                       TaskRuntimeProfile.BackpressureClass backpressureClass,
                                                       int batchSize,
                                                       int defaultMaxRetryCount,
                                                       int maxReadyItemsPerTask,
                                                       long interactiveWorkRetryDelayMillis,
                                                       long bulkWorkRetryDelayMillis,
                                                       boolean retryExpiredLeaseFromAnyActiveState,
                                                       boolean expiredLeaseFinalizesAsFailure) {
        var dispatchLane = workloadClass == TaskWorkloadClass.INTERACTIVE
                ? TaskRuntimeProfile.DispatchLane.INTERACTIVE
                : TaskRuntimeProfile.DispatchLane.BULK;
        var dispatchPriority = workloadClass == TaskWorkloadClass.INTERACTIVE
                ? TaskRuntimeProfile.DispatchPriority.HIGH
                : TaskRuntimeProfile.DispatchPriority.NORMAL;
        return new ResolvedTaskSchedulingPolicy(
                "task-1",
                "BATCH",
                workloadClass,
                dispatchLane,
                dispatchPriority,
                batchPolicy,
                leaseProfile,
                backpressureClass,
                ResolvedTaskSchedulingPolicy.DispatchCadence.RUNTIME_READY_POLLING,
                ResolvedTaskSchedulingPolicy.WorkerResourceMode.EXCLUSIVE,
                ResolvedTaskSchedulingPolicy.IdleClosePolicy.batchAllFinal(),
                new ResolvedTaskSchedulingPolicy.ClaimPolicy(batchPolicy, leaseProfile, 2, 30L),
                new ResolvedTaskSchedulingPolicy.RetryPolicy(
                        workloadClass,
                        75L,
                        interactiveWorkRetryDelayMillis,
                        bulkWorkRetryDelayMillis),
                new ResolvedTaskSchedulingPolicy.ResultFinalityPolicy(
                        retryExpiredLeaseFromAnyActiveState,
                        expiredLeaseFinalizesAsFailure),
                new ResolvedTaskSchedulingPolicy.BackpressurePolicy(
                        backpressureClass,
                        maxReadyItemsPerTask),
                batchSize,
                defaultMaxRetryCount,
                0);
    }
}
