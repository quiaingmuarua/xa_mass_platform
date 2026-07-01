package com.xa.mass.engine.policy;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.IdleClosePolicy;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;

import java.util.Objects;

/**
 * Central terminal-policy owner that keeps batch auto-convergence and session
 * keep-open semantics in one explicit decision point.
 */
public class ContractAwareTaskTerminalPolicy implements TaskTerminalPolicy {

    private final TaskTerminalPolicy batchPolicy;

    public ContractAwareTaskTerminalPolicy() {
        this(new AllWorkFinalTaskTerminalPolicy());
    }

    public ContractAwareTaskTerminalPolicy(TaskTerminalPolicy batchPolicy) {
        this.batchPolicy = Objects.requireNonNull(batchPolicy, "batchPolicy");
    }

    @Override
    public TaskTerminalPolicyDecision evaluate(Task task, TaskRuntimeProgressSnapshot stats, IdleClosePolicy idleClosePolicy) {
        if (task == null) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        if (idleClosePolicy != null && idleClosePolicy.enabled()) {
            return batchPolicy.evaluate(task, stats, idleClosePolicy);
        }
        return evaluateSession(task, stats);
    }

    private TaskTerminalPolicyDecision evaluateSession(Task task, TaskRuntimeProgressSnapshot stats) {
        // Session shells may stop accepting new items, but draining the current
        // runtime work set is not sufficient to end the session lifecycle.
        // Terminal closure stays explicit or policy-driven outside the
        // all-final batch convergence model.
        return TaskTerminalPolicyDecision.keepRunning();
    }
}
