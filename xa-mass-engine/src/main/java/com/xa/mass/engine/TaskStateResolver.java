package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.util.TraceEventLogger;

/**
 * Resolves task aggregate progress and terminal convergence from persisted task messages.
 */
class TaskStateResolver {

    private final TaskManager taskManager;

    TaskStateResolver(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    void updateTaskProgress(String taskId) {
        resolveTaskStateFromMessages(taskId);
    }

    TaskStateResolutionResult resolveTaskStateFromMessages(String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            return TaskStateResolutionResult.taskNotFound();
        }

        TaskStorage.TaskMessageStats stats = taskManager.getTaskMessageStats(taskId);
        task.setTaskSuccessNumber((int) stats.getSuccess());

        if (task.getStatus().isFinal()) {
            taskManager.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "ALREADY_FINAL", false,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "task already final");
            return TaskStateResolutionResult.alreadyFinal(
                    task.getStatus(),
                    task.getTerminalReason(),
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        TaskTerminalPolicyDecision decision = taskManager.getTaskTerminalPolicy().evaluate(task, stats);
        if (decision.getOutcome() != TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
            taskManager.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "NOT_FINALIZED", false,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "task remains non-final after progress evaluation");
            return TaskStateResolutionResult.notFinalized(
                    task.getStatus(),
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        TaskTerminalReason reason = decision.getTerminalReason();
        TaskStatus fromStatus = task.getStatus();
        boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
        if (result) {
            TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "all persisted messages finalized");
            TraceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "all persisted messages finalized");
            taskManager.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "FINALIZED_TO_TERMINAL", false,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "all persisted messages finalized");
            taskManager.getEventPublisher().publishTaskTerminal(task);
            return TaskStateResolutionResult.finalizedToTerminal(
                    reason,
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        taskManager.updateTask(task);
        emitTaskProgressSnapshot(task, stats, "FINALIZE_REJECTED", true,
                "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "task terminal transition was rejected");
        return TaskStateResolutionResult.notFinalized(
                task.getStatus(),
                stats.getTotal(),
                stats.getSuccess(),
                stats.getFailed()
        );
    }

    private void emitTaskProgressSnapshot(Task task,
                                          TaskStorage.TaskMessageStats stats,
                                          String resolutionOutcome,
                                          boolean needsTerminalClosure,
                                          String trigger,
                                          String source,
                                          String reason) {
        TraceEventLogger.taskProgressSnapshot(
                task,
                stats,
                resolutionOutcome,
                needsTerminalClosure,
                trigger,
                source,
                reason
        );
    }
}
