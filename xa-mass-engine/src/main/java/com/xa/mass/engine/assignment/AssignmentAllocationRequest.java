package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

public record AssignmentAllocationRequest(
        Task task,
        TaskStatus initialStatus,
        int readyWorkCount,
        int currentTaskWorkerCount
) {
}
