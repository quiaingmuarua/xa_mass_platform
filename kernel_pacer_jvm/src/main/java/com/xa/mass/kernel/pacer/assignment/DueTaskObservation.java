package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.Objects;

record DueTaskObservation(
        String taskId,
        TaskScoreState scoreState,
        TaskDescriptor descriptor
) {

    DueTaskObservation {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId must be non-empty");
        }
        Objects.requireNonNull(scoreState, "scoreState");
        Objects.requireNonNull(descriptor, "descriptor");
        if (!taskId.equals(descriptor.taskId())) {
            throw new IllegalArgumentException(
                    "Task observation descriptor identity mismatch"
            );
        }
    }
}
