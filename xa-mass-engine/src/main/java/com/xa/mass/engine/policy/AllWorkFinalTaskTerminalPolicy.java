package com.xa.mass.engine.policy;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.TaskWorkStats;

/**
 * Batch task terminal policy: a task reaches TERMINAL only when all engine
 * work items have reached a final runtime outcome after intake has been closed.
 */
public class AllWorkFinalTaskTerminalPolicy implements TaskTerminalPolicy {

    @Override
    public TaskTerminalPolicyDecision evaluate(Task task, TaskWorkStats stats) {
        if (task == null || task.getContract() != TaskContract.BATCH) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        if (stats.totalCount() <= 0) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        if (!task.isIntakeSealed()) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        if (stats.finalCount() != stats.totalCount()) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        return TaskTerminalPolicyDecision.finalizeToTerminal(determineTerminalReason(stats));
    }

    private TaskTerminalReason determineTerminalReason(TaskWorkStats stats) {
        if (stats.successCount() == stats.totalCount()) {
            return TaskTerminalReason.ALL_MESSAGES_SUCCEEDED;
        }
        if (stats.failedCount() + stats.expiredCount() == stats.totalCount()) {
            return TaskTerminalReason.ALL_MESSAGES_FAILED;
        }
        return TaskTerminalReason.MIXED_MESSAGE_RESULTS;
    }
}

