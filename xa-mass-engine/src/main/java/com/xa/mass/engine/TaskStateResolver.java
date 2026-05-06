package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.TaskWorkStats;

/**
 * Resolves task aggregate progress and terminal convergence from runtime-owned
 * work stats, not by scanning task-message snapshots.
 */
class TaskStateResolver {

    private final TaskStateRuntimePort stateRuntime;
    private final TraceEventLogger traceEventLogger;

    TaskStateResolver(TaskStateRuntimePort stateRuntime, TraceEventLogger traceEventLogger) {
        this.stateRuntime = stateRuntime;
        this.traceEventLogger = traceEventLogger;
    }

    void updateTaskProgress(String taskId) {
        resolveTaskState(taskId);
    }

    TaskStateResolutionResult resolveTaskState(String taskId) {
        Task task = stateRuntime.getTask(taskId);
        if (task == null) {
            return TaskStateResolutionResult.taskNotFound();
        }

        TaskWorkStats stats = stateRuntime.getTaskWorkStats(taskId);
        task.setTaskSuccessNumber((int) Math.min(stats.successCount(), Integer.MAX_VALUE));

        if (task.getStatus().isFinal()) {
            stateRuntime.updateTask(task);
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

        TaskTerminalPolicyDecision decision = stateRuntime.evaluateTerminalPolicy(task, stats);
        if (decision.getOutcome() != TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
            stateRuntime.updateTask(task);
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
            traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                    "RESOLVE_TASK_STATE", "TaskManager", "all work items finalized");
            traceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                    "RESOLVE_TASK_STATE", "TaskManager", "all work items finalized");
            stateRuntime.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "FINALIZED_TO_TERMINAL", false,
                    "RESOLVE_TASK_STATE", "TaskManager", "all work items finalized");
            stateRuntime.publishTaskTerminal(task);
            return TaskStateResolutionResult.finalizedToTerminal(
                    reason,
                    stats.totalCount(),
                    stats.successCount(),
                    stats.failedCount()
            );
        }

        stateRuntime.updateTask(task);
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
        traceEventLogger.taskProgressSnapshot(
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


