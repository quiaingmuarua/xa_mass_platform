package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;

/**
 * Resolved task-side scheduling view consumed by engine execution owners.
 *
 * <p>This value may parameterize queues, retry cadence, worker budget, and
 * gates. It does not own queue, lease, retry, expiry, or backpressure truth.</p>
 */
public record ResolvedTaskSchedulingPolicy(
        String taskId,
        String taskPolicyPreset,
        TaskWorkloadClass workloadClass,
        TaskRuntimeProfile.DispatchLane dispatchLane,
        TaskRuntimeProfile.DispatchPriority dispatchPriority,
        TaskRuntimeProfile.BatchPolicy batchPolicy,
        TaskRuntimeProfile.LeaseProfile leaseProfile,
        TaskRuntimeProfile.BackpressureClass backpressureClass,
        DispatchCadence dispatchCadence,
        WorkerResourceMode workerResourceMode,
        IdleClosePolicy idleClosePolicy,
        ClaimPolicy claimPolicy,
        RetryPolicy retryPolicy,
        ResultFinalityPolicy resultFinalityPolicy,
        BackpressurePolicy backpressurePolicy,
        int batchSize,
        int defaultMaxRetryCount,
        int minRequiredWorkerCount
) {

    public ResolvedTaskSchedulingPolicy {
        taskPolicyPreset = taskPolicyPreset == null || taskPolicyPreset.isBlank() ? "BATCH" : taskPolicyPreset;
        dispatchCadence = dispatchCadence == null ? DispatchCadence.RUNTIME_READY_POLLING : dispatchCadence;
        workerResourceMode = workerResourceMode == null ? WorkerResourceMode.EXCLUSIVE : workerResourceMode;
        idleClosePolicy = idleClosePolicy == null ? IdleClosePolicy.batchAllFinal() : idleClosePolicy;
        TaskRuntimeProfile profile = new TaskRuntimeProfile(
                workloadClass == null ? TaskWorkloadClass.BULK : workloadClass,
                dispatchLane == null ? TaskRuntimeProfile.DispatchLane.BULK : dispatchLane,
                dispatchPriority == null ? TaskRuntimeProfile.DispatchPriority.NORMAL : dispatchPriority,
                batchPolicy == null ? TaskRuntimeProfile.BatchPolicy.LARGE : batchPolicy,
                leaseProfile == null ? TaskRuntimeProfile.LeaseProfile.NORMAL : leaseProfile,
                backpressureClass == null ? TaskRuntimeProfile.BackpressureClass.BULK : backpressureClass
        );
        claimPolicy = claimPolicy == null ? ClaimPolicy.from(profile) : claimPolicy;
        retryPolicy = retryPolicy == null ? RetryPolicy.from(profile) : retryPolicy;
        resultFinalityPolicy = resultFinalityPolicy == null
                ? ResultFinalityPolicy.batch()
                : resultFinalityPolicy;
        backpressurePolicy = backpressurePolicy == null ? BackpressurePolicy.from(profile) : backpressurePolicy;
        batchSize = Math.max(1, batchSize);
        defaultMaxRetryCount = Math.max(0, defaultMaxRetryCount);
        minRequiredWorkerCount = Math.max(0, minRequiredWorkerCount);
    }

    public static ResolvedTaskSchedulingPolicy from(Task task, TaskRuntimeProfile profile) {
        return from(task, TaskPolicyPresetResolution.from(task, profile));
    }

    public static ResolvedTaskSchedulingPolicy from(Task task, TaskPolicyPresetResolution presetResolution) {
        TaskPolicyPresetResolution resolvedPreset = presetResolution != null
                ? presetResolution
                : TaskPolicyPresetResolution.from(task, defaultProfile());
        TaskRuntimeProfile resolvedProfile = resolvedPreset.runtimeProfile();
        return new ResolvedTaskSchedulingPolicy(
                task == null ? null : task.getTid(),
                resolvedPreset.taskPolicyPreset(),
                resolvedProfile.workloadClass(),
                resolvedProfile.dispatchLane(),
                resolvedProfile.dispatchPriority(),
                resolvedProfile.batchPolicy(),
                resolvedProfile.leaseProfile(),
                resolvedProfile.backpressureClass(),
                resolvedPreset.dispatchCadence(),
                resolvedPreset.workerResourceMode(),
                resolvedPreset.idleClosePolicy(),
                resolvedPreset.claimPolicy(),
                resolvedPreset.retryPolicy(),
                resolvedPreset.resultFinalityPolicy(),
                resolvedPreset.backpressurePolicy(),
                task == null ? 1 : task.getExecutionSpec().getBatchSize(),
                task == null ? 0 : task.getExecutionSpec().getDefaultMaxRetryCount(),
                task == null ? 0 : task.getMinRequiredWorkerCount()
        );
    }

    private static TaskRuntimeProfile defaultProfile() {
        return new TaskRuntimeProfile(
                TaskWorkloadClass.BULK,
                TaskRuntimeProfile.DispatchLane.BULK,
                TaskRuntimeProfile.DispatchPriority.NORMAL,
                TaskRuntimeProfile.BatchPolicy.LARGE,
                TaskRuntimeProfile.LeaseProfile.NORMAL,
                TaskRuntimeProfile.BackpressureClass.BULK
        );
    }

    public enum DispatchCadence {
        RUNTIME_READY_POLLING,
        SIGNAL_DRIVEN_DELAYED
    }

    public enum WorkerResourceMode {
        EXCLUSIVE,
        CAPACITY
    }

    public record IdleClosePolicy(boolean enabled, boolean requireIntakeSealed) {
        public static IdleClosePolicy batchAllFinal() {
            return new IdleClosePolicy(true, true);
        }

        public static IdleClosePolicy disabled() {
            return new IdleClosePolicy(false, false);
        }
    }

    public record ClaimPolicy(TaskRuntimeProfile.BatchPolicy batchPolicy,
                              TaskRuntimeProfile.LeaseProfile leaseProfile,
                              int smallPerWorkerCapacityLimit,
                              long shortLeaseSeconds) {
        public ClaimPolicy {
            batchPolicy = batchPolicy == null ? TaskRuntimeProfile.BatchPolicy.LARGE : batchPolicy;
            leaseProfile = leaseProfile == null ? TaskRuntimeProfile.LeaseProfile.NORMAL : leaseProfile;
            smallPerWorkerCapacityLimit = Math.max(1, smallPerWorkerCapacityLimit);
            shortLeaseSeconds = Math.max(1L, shortLeaseSeconds);
        }

        public static ClaimPolicy from(TaskRuntimeProfile profile) {
            return from(profile, defaultInteractivePerWorkerCap(), defaultShortLeaseSeconds());
        }

        public static ClaimPolicy from(TaskRuntimeProfile profile,
                                       int smallPerWorkerCapacityLimit,
                                       long shortLeaseSeconds) {
            TaskRuntimeProfile resolvedProfile = profile != null ? profile : defaultProfile();
            return new ClaimPolicy(resolvedProfile.batchPolicy(), resolvedProfile.leaseProfile(),
                    smallPerWorkerCapacityLimit, shortLeaseSeconds);
        }
    }

    public record RetryPolicy(TaskWorkloadClass workloadClass,
                              long interactiveAssignmentRetryDelayMillis,
                              long interactiveWorkRetryDelayMillis,
                              long bulkWorkRetryDelayMillis) {
        public RetryPolicy {
            workloadClass = workloadClass == null ? TaskWorkloadClass.BULK : workloadClass;
            interactiveAssignmentRetryDelayMillis = Math.max(1L, interactiveAssignmentRetryDelayMillis);
            interactiveWorkRetryDelayMillis = Math.max(0L, interactiveWorkRetryDelayMillis);
            bulkWorkRetryDelayMillis = Math.max(0L, bulkWorkRetryDelayMillis);
        }

        public static RetryPolicy from(TaskRuntimeProfile profile) {
            return from(profile,
                    defaultInteractiveAssignmentRetryDelayMillis(),
                    defaultInteractiveWorkRetryDelayMillis(),
                    defaultBulkWorkRetryDelayMillis());
        }

        public static RetryPolicy from(TaskRuntimeProfile profile,
                                       long interactiveAssignmentRetryDelayMillis,
                                       long interactiveWorkRetryDelayMillis,
                                       long bulkWorkRetryDelayMillis) {
            TaskRuntimeProfile resolvedProfile = profile != null ? profile : defaultProfile();
            return new RetryPolicy(
                    resolvedProfile.workloadClass(),
                    interactiveAssignmentRetryDelayMillis,
                    interactiveWorkRetryDelayMillis,
                    bulkWorkRetryDelayMillis
            );
        }
    }

    public record ResultFinalityPolicy(boolean retryExpiredLeaseFromAnyActiveState,
                                       boolean expiredLeaseFinalizesAsFailure) {
        public static ResultFinalityPolicy batch() {
            return new ResultFinalityPolicy(true, true);
        }

        public static ResultFinalityPolicy session() {
            return new ResultFinalityPolicy(false, false);
        }
    }

    public record BackpressurePolicy(TaskRuntimeProfile.BackpressureClass backpressureClass,
                                     int maxReadyItemsPerTask) {
        public BackpressurePolicy {
            backpressureClass = backpressureClass == null ? TaskRuntimeProfile.BackpressureClass.BULK
                    : backpressureClass;
            maxReadyItemsPerTask = maxReadyItemsPerTask <= 0 ? -1 : maxReadyItemsPerTask;
        }

        public static BackpressurePolicy from(TaskRuntimeProfile profile) {
            TaskRuntimeProfile resolvedProfile = profile != null ? profile : defaultProfile();
            int maxReadyItemsPerTask = resolvedProfile.backpressureClass() == TaskRuntimeProfile.BackpressureClass.INTERACTIVE
                    ? defaultInteractiveMaxReadyItemsPerTask()
                    : defaultBulkMaxReadyItemsPerTask();
            return from(profile, maxReadyItemsPerTask);
        }

        public static BackpressurePolicy from(TaskRuntimeProfile profile, int maxReadyItemsPerTask) {
            TaskRuntimeProfile resolvedProfile = profile != null ? profile : defaultProfile();
            return new BackpressurePolicy(resolvedProfile.backpressureClass(), maxReadyItemsPerTask);
        }
    }

    private static int defaultInteractivePerWorkerCap() {
        return Integer.getInteger("xa.mass.engine.interactivePerWorkerClaimLimit", 1);
    }

    private static long defaultShortLeaseSeconds() {
        return Long.getLong("xa.mass.engine.interactiveLeaseSeconds", 30L);
    }

    private static long defaultInteractiveAssignmentRetryDelayMillis() {
        return Long.getLong("xa.mass.engine.interactiveAssignmentRetryDelayMillis", 100L);
    }

    private static long defaultInteractiveWorkRetryDelayMillis() {
        return Long.getLong("xa.mass.engine.interactiveWorkRetryDelayMillis", 100L);
    }

    private static long defaultBulkWorkRetryDelayMillis() {
        return Long.getLong("xa.mass.engine.bulkWorkRetryDelayMillis", 0L);
    }

    private static int defaultInteractiveMaxReadyItemsPerTask() {
        return Integer.getInteger("xa.mass.engine.interactiveMaxReadyItemsPerTask", 10_000);
    }

    private static int defaultBulkMaxReadyItemsPerTask() {
        return Integer.getInteger("xa.mass.engine.bulkMaxReadyItemsPerTask", -1);
    }
}
