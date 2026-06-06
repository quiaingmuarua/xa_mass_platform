package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;

import java.util.Objects;

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
        taskPolicyPreset = requireText(taskPolicyPreset, "taskPolicyPreset");
        workloadClass = Objects.requireNonNull(workloadClass, "workloadClass");
        dispatchLane = Objects.requireNonNull(dispatchLane, "dispatchLane");
        dispatchPriority = Objects.requireNonNull(dispatchPriority, "dispatchPriority");
        batchPolicy = Objects.requireNonNull(batchPolicy, "batchPolicy");
        leaseProfile = Objects.requireNonNull(leaseProfile, "leaseProfile");
        backpressureClass = Objects.requireNonNull(backpressureClass, "backpressureClass");
        dispatchCadence = Objects.requireNonNull(dispatchCadence, "dispatchCadence");
        workerResourceMode = Objects.requireNonNull(workerResourceMode, "workerResourceMode");
        idleClosePolicy = Objects.requireNonNull(idleClosePolicy, "idleClosePolicy");
        claimPolicy = Objects.requireNonNull(claimPolicy, "claimPolicy");
        retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        resultFinalityPolicy = Objects.requireNonNull(resultFinalityPolicy, "resultFinalityPolicy");
        backpressurePolicy = Objects.requireNonNull(backpressurePolicy, "backpressurePolicy");
        batchSize = Math.max(1, batchSize);
        defaultMaxRetryCount = Math.max(0, defaultMaxRetryCount);
        minRequiredWorkerCount = Math.max(0, minRequiredWorkerCount);
    }

    public static ResolvedTaskSchedulingPolicy from(Task task, TaskPolicyPresetResolution presetResolution) {
        TaskPolicyPresetResolution resolvedPreset = presetResolution != null
                ? presetResolution
                : TaskPolicyPresetResolution.fromResolved(task, new TaskRuntimeProfileResolver());
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
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

        public static ClaimPolicy from(TaskRuntimeProfile profile,
                                       int smallPerWorkerCapacityLimit,
                                       long shortLeaseSeconds) {
            TaskRuntimeProfile resolvedProfile = Objects.requireNonNull(profile, "profile");
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

        public static RetryPolicy from(TaskRuntimeProfile profile,
                                       long interactiveAssignmentRetryDelayMillis,
                                       long interactiveWorkRetryDelayMillis,
                                       long bulkWorkRetryDelayMillis) {
            TaskRuntimeProfile resolvedProfile = Objects.requireNonNull(profile, "profile");
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

        public static BackpressurePolicy from(TaskRuntimeProfile profile, int maxReadyItemsPerTask) {
            TaskRuntimeProfile resolvedProfile = Objects.requireNonNull(profile, "profile");
            return new BackpressurePolicy(resolvedProfile.backpressureClass(), maxReadyItemsPerTask);
        }
    }
}
