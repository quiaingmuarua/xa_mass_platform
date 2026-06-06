package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.model.Task;
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
        TaskPolicyPresetDefinition definition = TaskPolicyPresetDefinition.forContract(
                taskPolicyPreset == null || taskPolicyPreset.isBlank()
                        ? null
                        : TaskContract.valueOf(taskPolicyPreset));
        TaskRuntimeProfile resolvedProfile = runtimeProfile != null ? runtimeProfile : definition.defaultRuntimeProfile();
        runtimeProfile = resolvedProfile;
        taskPolicyPreset = definition.contract().name();
        dispatchCadence = dispatchCadence == null ? definition.dispatchCadence() : dispatchCadence;
        workerResourceMode = workerResourceMode == null ? WorkerResourceMode.EXCLUSIVE : workerResourceMode;
        idleClosePolicy = idleClosePolicy == null ? definition.idleClosePolicy() : idleClosePolicy;
        TaskPolicyRuntimeDefaults defaults = TaskPolicyRuntimeDefaults.fromSystemProperties();
        claimPolicy = claimPolicy == null ? claimPolicy(resolvedProfile, defaults) : claimPolicy;
        retryPolicy = retryPolicy == null ? retryPolicy(resolvedProfile, defaults) : retryPolicy;
        resultFinalityPolicy = resultFinalityPolicy == null
                ? definition.resultFinalityPolicy()
                : resultFinalityPolicy;
        backpressurePolicy = backpressurePolicy == null ? backpressurePolicy(resolvedProfile, defaults)
                : backpressurePolicy;
    }

    static TaskPolicyPresetResolution from(Task task, TaskRuntimeProfile profile) {
        TaskContract contract = TaskPolicyPresetDefinition.defaultContract(task == null ? null : task.getContract());
        TaskPolicyPresetDefinition definition = TaskPolicyPresetDefinition.forContract(contract);
        return from(task, definition, profile, TaskPolicyRuntimeDefaults.fromSystemProperties());
    }

    static TaskPolicyPresetResolution from(Task task,
                                           TaskPolicyPresetDefinition definition,
                                           TaskRuntimeProfile profile,
                                           TaskPolicyRuntimeDefaults defaults) {
        TaskPolicyPresetDefinition resolvedDefinition = definition != null
                ? definition
                : TaskPolicyPresetDefinition.forContract(task == null ? null : task.getContract());
        TaskRuntimeProfile resolvedProfile = profile != null ? profile : resolvedDefinition.defaultRuntimeProfile();
        TaskPolicyRuntimeDefaults resolvedDefaults = defaults != null
                ? defaults
                : TaskPolicyRuntimeDefaults.fromSystemProperties();
        return new TaskPolicyPresetResolution(
                resolvedDefinition.contract().name(),
                resolvedProfile,
                resolvedDefinition.dispatchCadence(),
                resolveWorkerResourceMode(task),
                resolvedDefinition.idleClosePolicy(),
                claimPolicy(resolvedProfile, resolvedDefaults),
                retryPolicy(resolvedProfile, resolvedDefaults),
                resolvedDefinition.resultFinalityPolicy(),
                backpressurePolicy(resolvedProfile, resolvedDefaults)
        );
    }

    private static WorkerResourceMode resolveWorkerResourceMode(Task task) {
        return task == null || task.getExecutionSpec() == null || task.getExecutionSpec().isForeground()
                ? WorkerResourceMode.EXCLUSIVE
                : WorkerResourceMode.CAPACITY;
    }

    private static ClaimPolicy claimPolicy(TaskRuntimeProfile profile, TaskPolicyRuntimeDefaults defaults) {
        return ClaimPolicy.from(
                profile,
                defaults.interactivePerWorkerClaimLimit(),
                defaults.interactiveLeaseSeconds()
        );
    }

    private static RetryPolicy retryPolicy(TaskRuntimeProfile profile, TaskPolicyRuntimeDefaults defaults) {
        return RetryPolicy.from(
                profile,
                defaults.interactiveAssignmentRetryDelayMillis(),
                defaults.interactiveWorkRetryDelayMillis(),
                defaults.bulkWorkRetryDelayMillis()
        );
    }

    private static BackpressurePolicy backpressurePolicy(TaskRuntimeProfile profile, TaskPolicyRuntimeDefaults defaults) {
        TaskRuntimeProfile resolvedProfile = profile != null ? profile
                : TaskPolicyPresetDefinition.forContract(null).defaultRuntimeProfile();
        int maxReadyItemsPerTask =
                resolvedProfile.backpressureClass() == TaskRuntimeProfile.BackpressureClass.INTERACTIVE
                        ? defaults.interactiveMaxReadyItemsPerTask()
                        : defaults.bulkMaxReadyItemsPerTask();
        return BackpressurePolicy.from(resolvedProfile, maxReadyItemsPerTask);
    }

    static TaskRuntimeProfile profileForTaskOrPreset(Task task,
                                                     TaskPolicyPresetDefinition definition,
                                                     com.xa.mass.engine.runtime.TaskRuntimeProfileResolver profileResolver) {
        TaskPolicyPresetDefinition resolvedDefinition = definition != null
                ? definition
                : TaskPolicyPresetDefinition.forContract(task == null ? null : task.getContract());
        if (task == null || task.getExecutionSpec() == null || task.getExecutionSpec().getWorkloadClass() == null) {
            return resolvedDefinition.defaultRuntimeProfile();
        }
        return profileResolver.resolve(task);
    }

    static TaskPolicyPresetDefinition definitionFor(Task task) {
        return TaskPolicyPresetDefinition.forContract(task == null ? null : task.getContract());
    }

    static TaskPolicyRuntimeDefaults runtimeDefaults() {
        return TaskPolicyRuntimeDefaults.fromSystemProperties();
    }

    static TaskPolicyPresetResolution fromResolved(Task task,
                                                   com.xa.mass.engine.runtime.TaskRuntimeProfileResolver profileResolver) {
        TaskPolicyPresetDefinition definition = definitionFor(task);
        return from(
                task,
                definition,
                profileForTaskOrPreset(task, definition, profileResolver),
                runtimeDefaults()
        );
    }
}
