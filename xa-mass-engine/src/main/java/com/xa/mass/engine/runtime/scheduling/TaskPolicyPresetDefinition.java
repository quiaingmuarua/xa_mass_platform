package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.DispatchCadence;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.IdleClosePolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.ResultFinalityPolicy;

/**
 * Internal static preset table for public task contract values.
 *
 * <p>This is not a product policy catalog. It only centralizes current
 * behavior-equivalent defaults for the public task-shape presets.</p>
 */
public record TaskPolicyPresetDefinition(
        TaskContract contract,
        TaskRuntimeProfile defaultRuntimeProfile,
        DispatchCadence dispatchCadence,
        IdleClosePolicy idleClosePolicy,
        ResultFinalityPolicy resultFinalityPolicy
) {

    private static final TaskPolicyPresetDefinition BATCH = new TaskPolicyPresetDefinition(
            TaskContract.BATCH,
            bulkProfile(),
            DispatchCadence.RUNTIME_READY_POLLING,
            IdleClosePolicy.batchAllFinal(),
            ResultFinalityPolicy.batch()
    );

    private static final TaskPolicyPresetDefinition SESSION = new TaskPolicyPresetDefinition(
            TaskContract.SESSION,
            interactiveProfile(),
            DispatchCadence.SIGNAL_DRIVEN_DELAYED,
            IdleClosePolicy.disabled(),
            ResultFinalityPolicy.session()
    );

    public static TaskPolicyPresetDefinition forContract(TaskContract contract) {
        return switch (defaultContract(contract)) {
            case SESSION -> SESSION;
            case BATCH -> BATCH;
        };
    }

    public static TaskContract defaultContract(TaskContract contract) {
        return contract == null ? TaskContract.BATCH : contract;
    }

    private static TaskRuntimeProfile interactiveProfile() {
        return new TaskRuntimeProfile(
                TaskWorkloadClass.INTERACTIVE,
                TaskRuntimeProfile.DispatchLane.INTERACTIVE,
                TaskRuntimeProfile.DispatchPriority.HIGH,
                TaskRuntimeProfile.BatchPolicy.SMALL,
                TaskRuntimeProfile.LeaseProfile.SHORT,
                TaskRuntimeProfile.BackpressureClass.INTERACTIVE
        );
    }

    private static TaskRuntimeProfile bulkProfile() {
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
