package com.xa.mass.engine.policy;

import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.storage.TaskStorage;

/**
 * Current mainline policy: a task reaches TERMINAL only when all persisted
 * task messages are already in final states.
 */
public class AllMessagesFinalTaskTerminalPolicy implements TaskTerminalPolicy {

    @Override
    public TaskTerminalPolicyDecision evaluate(Task task, TaskStorage.TaskMessageStats stats) {
        if (stats.getTotal() <= 0) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        // Tasks only auto-terminate after intake has been sealed.
        if (task.getIntakeStatus() == TaskIntakeStatus.OPEN) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        if (stats.getSuccess() + stats.getFailed() + stats.getExpired() != stats.getTotal()) {
            return TaskTerminalPolicyDecision.keepRunning();
        }
        return TaskTerminalPolicyDecision.finalizeToTerminal(determineTerminalReason(stats));
    }

    private TaskTerminalReason determineTerminalReason(TaskStorage.TaskMessageStats stats) {
        if (stats.getSuccess() == stats.getTotal()) {
            return TaskTerminalReason.ALL_MESSAGES_SUCCEEDED;
        }
        if (stats.getFailed() + stats.getExpired() == stats.getTotal()) {
            return TaskTerminalReason.ALL_MESSAGES_FAILED;
        }
        return TaskTerminalReason.MIXED_MESSAGE_RESULTS;
    }
}
