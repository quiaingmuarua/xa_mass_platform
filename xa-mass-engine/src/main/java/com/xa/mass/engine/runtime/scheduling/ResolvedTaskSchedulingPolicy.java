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
        TaskWorkloadClass workloadClass,
        TaskRuntimeProfile.DispatchLane dispatchLane,
        TaskRuntimeProfile.DispatchPriority dispatchPriority,
        TaskRuntimeProfile.BatchPolicy batchPolicy,
        TaskRuntimeProfile.LeaseProfile leaseProfile,
        TaskRuntimeProfile.BackpressureClass backpressureClass,
        int batchSize,
        int defaultMaxRetryCount,
        int minRequiredWorkerCount
) {

    public ResolvedTaskSchedulingPolicy {
        batchSize = Math.max(1, batchSize);
        defaultMaxRetryCount = Math.max(0, defaultMaxRetryCount);
        minRequiredWorkerCount = Math.max(0, minRequiredWorkerCount);
    }

    public static ResolvedTaskSchedulingPolicy from(Task task, TaskRuntimeProfile profile) {
        TaskRuntimeProfile resolvedProfile = profile != null ? profile : defaultProfile();
        return new ResolvedTaskSchedulingPolicy(
                task == null ? null : task.getTid(),
                resolvedProfile.workloadClass(),
                resolvedProfile.dispatchLane(),
                resolvedProfile.dispatchPriority(),
                resolvedProfile.batchPolicy(),
                resolvedProfile.leaseProfile(),
                resolvedProfile.backpressureClass(),
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
}
