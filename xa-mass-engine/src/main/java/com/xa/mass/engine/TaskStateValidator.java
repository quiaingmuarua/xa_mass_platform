package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates runtime task state without scanning compatibility projection
 * residue.
 */
class TaskStateValidator {

    private final TaskStateRuntimePort stateRuntime;
    private final TraceEventLogger traceEventLogger;

    TaskStateValidator(TaskStateRuntimePort stateRuntime,
                       TraceEventLogger traceEventLogger) {
        this.stateRuntime = stateRuntime;
        this.traceEventLogger = traceEventLogger;
    }

    TaskStateValidationResult validateTaskState(String taskId) {
        RuntimeValidationSnapshot snapshot = computeRuntimeValidation(taskId);
        TaskStateValidationResult result = snapshot.toResult(TaskStateValidationResult.Scope.RUNTIME);
        if (!result.isValid() || result.isNeedsResolution()) {
            emitTaskStateValidationSummary(
                    taskId,
                    result,
                    result.isNeedsResolution()
                            ? "task requires explicit terminal reconciliation"
                            : snapshot.task() == null
                            ? "task not found"
                            : "task validation found invariant violations",
                    "VALIDATE_TASK_STATE"
            );
        }
        return result;
    }

    RuntimeValidationSnapshot computeRuntimeValidation(String taskId) {
        Task task = getTask(taskId);
        if (task == null) {
            return new RuntimeValidationSnapshot(
                    null,
                    emptyProgressSnapshot(taskId),
                    List.of(TaskStateValidationResult.ViolationCode.TASK_NOT_FOUND),
                    false
            );
        }

        TaskRuntimeProgressSnapshot stats = getTaskRuntimeProgressSnapshot(taskId);
        List<TaskStateValidationResult.ViolationCode> violations = new ArrayList<>();
        if (task.getTaskEligibleNumber() < 0) {
            violations.add(TaskStateValidationResult.ViolationCode.NEGATIVE_ELIGIBLE_COUNT);
        }
        if (task.getTaskSuccessNumber() < 0) {
            violations.add(TaskStateValidationResult.ViolationCode.NEGATIVE_SUCCESS_COUNT);
        }
        if (task.getTaskSuccessNumber() > task.getTaskEligibleNumber()) {
            violations.add(TaskStateValidationResult.ViolationCode.SUCCESS_EXCEEDS_ELIGIBLE);
        }
        if (task.getTaskNonSuccessNumber() != task.getTaskEligibleNumber() - task.getTaskSuccessNumber()) {
            violations.add(TaskStateValidationResult.ViolationCode.NON_SUCCESS_COUNT_MISMATCH);
        }
        if (task.getStatus() == TaskStatus.BLOCKED && task.getHoldReason() == null) {
            violations.add(TaskStateValidationResult.ViolationCode.BLOCKED_HOLD_REASON_MISSING);
        }
        if (task.getStatus() != TaskStatus.BLOCKED && task.getHoldReason() != null) {
            violations.add(TaskStateValidationResult.ViolationCode.HOLD_REASON_PRESENT_ON_NON_BLOCKED);
        }

        boolean finalStatus = task.getStatus() != null && task.getStatus().isFinal();
        boolean hasTerminalReason = task.getTerminalReason() != null;
        if (finalStatus && task.isIntakeOpen()) {
            violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_TASK_WITH_OPEN_INTAKE);
        }
        if (finalStatus && !hasTerminalReason) {
            violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISSING);
        }
        if (!finalStatus && hasTerminalReason) {
            violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_PRESENT_ON_NON_TERMINAL);
        }

        if (finalStatus && hasTerminalReason) {
            switch (task.getTerminalReason()) {
                case ALL_MESSAGES_SUCCEEDED -> {
                    if (!(stats.totalCount() > 0 && stats.successCount() == stats.totalCount()
                            && stats.failedCount() == 0 && stats.expiredCount() == 0 && stats.processingCount() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED);
                    }
                }
                case ALL_MESSAGES_FAILED -> {
                    if (!(stats.totalCount() > 0 && stats.failedCount() + stats.expiredCount() == stats.totalCount()
                            && stats.successCount() == 0 && stats.processingCount() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_FAILED);
                    }
                }
                case MIXED_MESSAGE_RESULTS -> {
                    boolean mixed = stats.totalCount() > 0
                            && stats.successCount() > 0
                            && stats.failedCount() + stats.expiredCount() > 0
                            && stats.finalCount() == stats.totalCount()
                            && stats.processingCount() == 0;
                    if (!mixed) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_MIXED_RESULTS);
                    }
                }
                case MANUAL_CANCELLED -> {
                    // Manual cancel is allowed regardless of message finality snapshot.
                }
                case MAX_RUNTIME_REACHED, SUCCESS_RATE_REACHED, RETRY_BUDGET_EXHAUSTED -> {
                    // Policy-driven terminal reasons are allowed to close the task
                    // independently of the current compatibility projection shape.
                }
            }
        }

        boolean needsResolution = !finalStatus
                && evaluateTerminalPolicy(task, stats).getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL;
        return new RuntimeValidationSnapshot(task, stats, List.copyOf(violations), needsResolution);
    }

    void emitTaskStateValidationSummary(String taskId,
                                        TaskStateValidationResult validationResult,
                                        String reason,
                                        String trigger) {
        String violationSummary = validationResult.getViolations().stream()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        traceEventLogger.taskStateValidationSummary(
                taskId,
                validationResult.getStatus(),
                validationResult.getTerminalReason(),
                validationResult.getTotalMessages(),
                validationResult.getSuccessMessages(),
                validationResult.getFailedMessages(),
                validationResult.getProcessingMessages(),
                validationResult.isValid(),
                validationResult.isNeedsResolution(),
                validationResult.getViolations().size(),
                violationSummary,
                trigger,
                "TaskManager",
                validationResult.getScope().name(),
                reason,
                validationResult.isValid() && !validationResult.isNeedsResolution() ? "SUCCESS" : "ANOMALY"
        );
    }

    private Task getTask(String taskId) {
        return stateRuntime.getTask(taskId);
    }

    private TaskRuntimeProgressSnapshot getTaskRuntimeProgressSnapshot(String taskId) {
        return stateRuntime.getTaskRuntimeProgressSnapshot(taskId);
    }

    private TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskRuntimeProgressSnapshot stats) {
        return stateRuntime.evaluateTerminalPolicy(task, stats);
    }

    record RuntimeValidationSnapshot(Task task,
                                     TaskRuntimeProgressSnapshot stats,
                                     List<TaskStateValidationResult.ViolationCode> violations,
                                     boolean needsResolution) {

        RuntimeValidationSnapshot {
            stats = stats == null ? emptyProgressSnapshot(task != null ? task.getTid() : null) : stats;
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        TaskStateValidationResult toResult(TaskStateValidationResult.Scope scope) {
            return new TaskStateValidationResult(
                    violations.isEmpty(),
                    needsResolution,
                    task != null ? task.getStatus() : null,
                    task != null ? task.getTerminalReason() : null,
                    stats.totalCount(),
                    stats.successCount(),
                    stats.failedCount(),
                    stats.processingCount(),
                    scope,
                    violations
            );
        }
    }

    private static TaskRuntimeProgressSnapshot emptyProgressSnapshot(String taskId) {
        return TaskRuntimeProgressSnapshot.empty(taskId == null || taskId.isBlank() ? "unknown" : taskId);
    }
}

