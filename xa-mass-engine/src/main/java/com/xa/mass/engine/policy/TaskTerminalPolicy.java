package com.xa.mass.engine.policy;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.IdleClosePolicy;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;

/**
 * Decides whether the current task/message aggregate should keep running or
 * be closed to TERMINAL.
 *
 * <p>This is the extension seam for future task-level completion rules such as:
 * max runtime, success-rate thresholds, or retry-budget exhaustion.
 */
public interface TaskTerminalPolicy {

    TaskTerminalPolicyDecision evaluate(Task task, TaskRuntimeProgressSnapshot stats, IdleClosePolicy idleClosePolicy);
}
