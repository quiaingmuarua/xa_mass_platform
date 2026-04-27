package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.work.TaskWorkStats;

/**
 * Resolves task aggregate progress and terminal convergence from runtime-owned
 * work stats, not by scanning task-message snapshots.
 */
class TaskStateResolver {

    private final TaskManager taskManager;

    TaskStateResolver(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    void updateTaskProgress(String taskId) {
        resolveTaskState(taskId);
    }

    TaskStateResolutionResult resolveTaskState(String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            return TaskStateResolutionResult.taskNotFound();
        }

        TaskWorkStats stats = taskManager.getTaskWorkRuntime().stats(taskId);
        task.setTaskSuccessNumber((int) Math.min(stats.successCount(), Integer.MAX_VALUE));

        if (task.getStatus().isFinal()) {
            taskManager.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "ALREADY_FINAL", false,
                    "RESOLVE_TASK_STATE", "TaskManager", "task already final");
            return TaskStateResolutionResult.alreadyFinal(
                    task.getStatus(),
                    task.getTerminalReason(),
                    stats.totalCount(),
                    stats.successCount(),
                    stats.failedCount()
            );
        }

        TaskTerminalPolicyDecision decision = taskManager.getTaskTerminalPolicy().evaluate(task, stats);
        if (decision.getOutcome() != TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
            taskManager.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "NOT_FINALIZED", false,
                    "RESOLVE_TASK_STATE", "TaskManager", "task remains non-final after progress evaluation");
            return TaskStateResolutionResult.notFinalized(
                    task.getStatus(),
                    stats.totalCount(),
                    stats.successCount(),
                    stats.failedCount()
            );
        }

        TaskTerminalReason reason = decision.getTerminalReason();
        TaskStatus fromStatus = task.getStatus();
        boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
        if (result) {
            TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                    "RESOLVE_TASK_STATE", "TaskManager", "all work items finalized");
            TraceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                    "RESOLVE_TASK_STATE", "TaskManager", "all work items finalized");
            taskManager.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "FINALIZED_TO_TERMINAL", false,
                    "RESOLVE_TASK_STATE", "TaskManager", "all work items finalized");
            taskManager.getEventPublisher().publishTaskTerminal(task);
            return TaskStateResolutionResult.finalizedToTerminal(
                    reason,
                    stats.totalCount(),
                    stats.successCount(),
                    stats.failedCount()
            );
        }

        taskManager.updateTask(task);
        emitTaskProgressSnapshot(task, stats, "FINALIZE_REJECTED", true,
                "RESOLVE_TASK_STATE", "TaskManager", "task terminal transition was rejected");
        return TaskStateResolutionResult.notFinalized(
                task.getStatus(),
                stats.totalCount(),
                stats.successCount(),
                stats.failedCount()
        );
    }

    private void emitTaskProgressSnapshot(Task task,
                                          TaskWorkStats stats,
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

