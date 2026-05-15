package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

/**
 * Default-compatible refill policy.
 *
 * <p>The policy preserves the existing behavior: a RUNNING task with
 * runtime-ready work should re-enter assignment after a worker slot is
 * released.</p>
 */
public final class DefaultAssignmentRefillPolicy implements AssignmentRefillPolicy {

    @Override
    public AssignmentRefillDecision decide(AssignmentRefillRequest request) {
        Task task = request == null ? null : request.task();
        if (task == null) {
            return AssignmentRefillDecision.skip("task is absent");
        }
        if (task.getStatus() != TaskStatus.RUNNING) {
            return AssignmentRefillDecision.skip("task status is not running: " + task.getStatus());
        }
        if (!request.hasDispatchReadyWork()) {
            return AssignmentRefillDecision.skip("no runtime-ready work for refill");
        }
        return AssignmentRefillDecision.requestDispatch("running task has runtime-ready work after resource release");
    }
}
