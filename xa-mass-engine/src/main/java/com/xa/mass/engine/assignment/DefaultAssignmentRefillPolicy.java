package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

/**
 * Default-compatible refill policy.
 */
public final class DefaultAssignmentRefillPolicy implements AssignmentRefillPolicy {

    @Override
    public AssignmentRefillDecision decide(AssignmentRefillRequest request) {
        Task task = request == null ? null : request.task();
        if (task == null) {
            return AssignmentRefillDecision.skip("task is absent");
        }
        TaskStatus status = task.getStatus();
        if (status != null && status.isFinal()) {
            return AssignmentRefillDecision.skip("task is terminal: " + status);
        }
        if (!request.hasDispatchReadyWork()) {
            return AssignmentRefillDecision.skip("no runtime-ready work for refill");
        }
        return AssignmentRefillDecision.requestDispatch("task has runtime-ready work after resource release");
    }
}
