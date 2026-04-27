package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;

/**
 * Internal normalized runtime profile for task-level scheduling behavior.
 *
 * <p>This is engine-only truth used by assignment, retry, and trace codepaths.
 * It is not a repo-external contract surface.
 */
public record TaskRuntimeProfile(
        TaskWorkloadClass workloadClass,
        DispatchLane dispatchLane,
        DispatchPriority dispatchPriority,
        BatchPolicy batchPolicy,
        LeaseProfile leaseProfile,
        BackpressureClass backpressureClass
) {

    public enum DispatchLane {
        INTERACTIVE,
        BULK
    }

    public enum DispatchPriority {
        HIGH,
        NORMAL
    }

    public enum BatchPolicy {
        SMALL,
        LARGE
    }

    public enum LeaseProfile {
        SHORT,
        NORMAL
    }

    public enum BackpressureClass {
        INTERACTIVE,
        BULK
    }
}
