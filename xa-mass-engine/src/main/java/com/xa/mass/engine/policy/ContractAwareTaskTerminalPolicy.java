package com.xa.mass.engine.policy;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.TaskWorkStats;

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
    public TaskTerminalPolicyDecision evaluate(Task task, TaskWorkStats stats) {
        if (task == null) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        return switch (task.getContract()) {
            case BATCH -> batchPolicy.evaluate(task, stats);
            case SESSION -> evaluateSession(task, stats);
        };
    }

    private TaskTerminalPolicyDecision evaluateSession(Task task, TaskWorkStats stats) {
        if (task.getIntakeStatus() == TaskIntakeStatus.SEALED
                && stats.totalCount() > 0
                && stats.finalCount() == stats.totalCount()) {
            return determineSessionTerminalReason(stats);
        }
        return TaskTerminalPolicyDecision.keepRunning();
    }

    private TaskTerminalPolicyDecision determineSessionTerminalReason(TaskWorkStats stats) {
        if (stats.successCount() == stats.totalCount()) {
            return TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED);
        }
        if (stats.failedCount() + stats.expiredCount() == stats.totalCount()) {
            return TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.ALL_MESSAGES_FAILED);
        }
        return TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.MIXED_MESSAGE_RESULTS);
    }
}
