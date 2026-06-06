package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.policy.TaskPolicyPresetSemantics;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.BackpressurePolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.ClaimPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.DispatchCadence;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.IdleClosePolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.ResultFinalityPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.RetryPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.WorkerResourceMode;

/**
 * Behavior-equivalent resolved preset output derived from legacy task fields.
 */
public record TaskPolicyPresetResolution(
        String taskPolicyPreset,
        TaskRuntimeProfile runtimeProfile,
        DispatchCadence dispatchCadence,
        WorkerResourceMode workerResourceMode,
        IdleClosePolicy idleClosePolicy,
        ClaimPolicy claimPolicy,
        RetryPolicy retryPolicy,
        ResultFinalityPolicy resultFinalityPolicy,
        BackpressurePolicy backpressurePolicy
) {

    public TaskPolicyPresetResolution {
        TaskRuntimeProfile resolvedProfile = runtimeProfile != null ? runtimeProfile : defaultRuntimeProfile();
        runtimeProfile = resolvedProfile;
        taskPolicyPreset = taskPolicyPreset == null || taskPolicyPreset.isBlank() ? TaskContract.BATCH.name()
                : taskPolicyPreset;
        dispatchCadence = dispatchCadence == null ? DispatchCadence.RUNTIME_READY_POLLING : dispatchCadence;
        workerResourceMode = workerResourceMode == null ? WorkerResourceMode.EXCLUSIVE : workerResourceMode;
        idleClosePolicy = idleClosePolicy == null ? IdleClosePolicy.batchAllFinal() : idleClosePolicy;
        claimPolicy = claimPolicy == null ? ClaimPolicy.from(resolvedProfile) : claimPolicy;
        retryPolicy = retryPolicy == null ? RetryPolicy.from(resolvedProfile) : retryPolicy;
        resultFinalityPolicy = resultFinalityPolicy == null
                ? ResultFinalityPolicy.batch()
                : resultFinalityPolicy;
        backpressurePolicy = backpressurePolicy == null ? BackpressurePolicy.from(resolvedProfile) : backpressurePolicy;
    }

    static TaskPolicyPresetResolution from(Task task, TaskRuntimeProfile profile) {
        TaskContract contract = TaskPolicyPresetSemantics.defaultContract(task == null ? null : task.getContract());
        TaskRuntimeProfile resolvedProfile = profile != null ? profile : defaultRuntimeProfile();
        return new TaskPolicyPresetResolution(
                contract.name(),
                resolvedProfile,
                dispatchCadenceFor(contract),
                resolveWorkerResourceMode(task),
                contract == TaskContract.BATCH ? IdleClosePolicy.batchAllFinal() : IdleClosePolicy.disabled(),
                ClaimPolicy.from(resolvedProfile),
                RetryPolicy.from(resolvedProfile),
                contract == TaskContract.BATCH ? ResultFinalityPolicy.batch() : ResultFinalityPolicy.session(),
                BackpressurePolicy.from(resolvedProfile)
        );
    }

    private static DispatchCadence dispatchCadenceFor(TaskContract contract) {
        return contract == TaskContract.SESSION
                ? DispatchCadence.SIGNAL_DRIVEN_DELAYED
                : DispatchCadence.RUNTIME_READY_POLLING;
    }

    private static WorkerResourceMode resolveWorkerResourceMode(Task task) {
        return task == null || task.getExecutionSpec() == null || task.getExecutionSpec().isForeground()
                ? WorkerResourceMode.EXCLUSIVE
                : WorkerResourceMode.CAPACITY;
    }

    private static TaskRuntimeProfile defaultRuntimeProfile() {
        return new TaskRuntimeProfile(
                com.xa.mass.base.enums.task.TaskWorkloadClass.BULK,
                TaskRuntimeProfile.DispatchLane.BULK,
                TaskRuntimeProfile.DispatchPriority.NORMAL,
                TaskRuntimeProfile.BatchPolicy.LARGE,
                TaskRuntimeProfile.LeaseProfile.NORMAL,
                TaskRuntimeProfile.BackpressureClass.BULK
        );
    }
}
