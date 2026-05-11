package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;

/**
 * Resolves the engine's standard runtime profile from the explicit task
 * workload class.
 */
public class TaskRuntimeProfileResolver {

    public TaskRuntimeProfile resolve(Task task) {
        TaskWorkloadClass workloadClass = task != null && task.getExecutionSpec().getWorkloadClass() != null
                ? task.getExecutionSpec().getWorkloadClass()
                : TaskWorkloadClass.BULK;

        return switch (workloadClass) {
            case INTERACTIVE -> new TaskRuntimeProfile(
                    TaskWorkloadClass.INTERACTIVE,
                    TaskRuntimeProfile.DispatchLane.INTERACTIVE,
                    TaskRuntimeProfile.DispatchPriority.HIGH,
                    TaskRuntimeProfile.BatchPolicy.SMALL,
                    TaskRuntimeProfile.LeaseProfile.SHORT,
                    TaskRuntimeProfile.BackpressureClass.INTERACTIVE
            );
            case BULK -> new TaskRuntimeProfile(
                    TaskWorkloadClass.BULK,
                    TaskRuntimeProfile.DispatchLane.BULK,
                    TaskRuntimeProfile.DispatchPriority.NORMAL,
                    TaskRuntimeProfile.BatchPolicy.LARGE,
                    TaskRuntimeProfile.LeaseProfile.NORMAL,
                    TaskRuntimeProfile.BackpressureClass.BULK
            );
        };
    }
}
