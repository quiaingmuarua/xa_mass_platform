package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

public record AssignmentAllocationPlan(
        Task task,
        TaskStatus initialStatus,
        int readyWorkCount,
        int rawDesiredDispatchWorkerCount,
        int desiredDispatchWorkerCount,
        int requiredStartWorkerCount,
        int requestedMatchCount,
        int dispatchCandidateLimit,
        Integer workerBudget,
        int currentTaskWorkerCount,
        boolean budgetLimited
) {
}
