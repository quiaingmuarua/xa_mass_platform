package com.xa.mass.engine.policy;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.TaskExecutionSpec;

/**
 * Temporary convergence seam for legacy task preset interpretation.
 *
 * <p>This is not the final policy owner. It exists to shrink scattered direct
 * reads of {@link TaskContract} and workload class defaults while runtime
 * behavior moves to resolved scheduling policy fields.</p>
 */
public final class TaskPolicyPresetSemantics {

    private TaskPolicyPresetSemantics() {
    }

    public static TaskContract defaultContract(TaskContract contract) {
        return contract == null ? TaskContract.BATCH : contract;
    }

    public static TaskWorkloadClass defaultWorkloadClassFor(TaskContract contract,
                                                            TaskExecutionSpec normalizedSpec) {
        if (normalizedSpec != null && normalizedSpec.getWorkloadClass() != null) {
            return normalizedSpec.getWorkloadClass();
        }
        return defaultContract(contract) == TaskContract.SESSION
                ? TaskWorkloadClass.INTERACTIVE
                : TaskWorkloadClass.BULK;
    }
}
