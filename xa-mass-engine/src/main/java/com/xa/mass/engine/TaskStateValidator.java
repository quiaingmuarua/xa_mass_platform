package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.TaskWorkStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates runtime task state and, when requested explicitly, audits the
 * persisted TaskMsg projection for deeper consistency issues.
 */
class TaskStateValidator {

    private final TaskStateRuntimePort stateRuntime;
    private final TaskCompatibilityProjectionAccess compatibilityProjectionAccess;
    private final TraceEventLogger traceEventLogger;

    TaskStateValidator(TaskStateRuntimePort stateRuntime,
                       TaskCompatibilityProjectionAccess compatibilityProjectionAccess,
                       TraceEventLogger traceEventLogger) {
        this.stateRuntime = stateRuntime;
        this.compatibilityProjectionAccess = compatibilityProjectionAccess;
        this.traceEventLogger = traceEventLogger;
    }

    TaskStateValidationResult validateTaskState(String taskId) {
        return validateTaskState(taskId, false);
    }

    TaskStateValidationResult auditTaskProjectionState(String taskId) {
        return validateTaskState(taskId, true);
    }

    private TaskStateValidationResult validateTaskState(String taskId, boolean projectionAudit) {
        Task task = getTask(taskId);
        if (task == null) {
            TaskStateValidationResult result = new TaskStateValidationResult(
                    false,
                    false,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    projectionAudit
                            ? TaskStateValidationResult.Scope.PROJECTION_AUDIT
                            : TaskStateValidationResult.Scope.RUNTIME,
                    List.of(TaskStateValidationResult.ViolationCode.TASK_NOT_FOUND)
            );
            emitTaskStateValidationSummary(
                    taskId,
                    result,
                    "task not found",
                    projectionAudit ? "AUDIT_TASK_PROJECTION_STATE" : "VALIDATE_TASK_STATE"
            );
            return result;
        }

        TaskWorkStats stats = getTaskWorkStats(taskId);
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
        if (finalStatus
                && task.getIntakeStatus() == TaskIntakeStatus.OPEN
                && (!hasTerminalReason || !task.getTerminalReason().allowsOpenIntakeClosure())) {
            violations.add(TaskStateValidationResult.ViolationCode.OPEN_INTAKE_FINALIZED_NON_MANUALLY);
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
                    // independently of the current TaskMsg aggregate shape.
                }
            }
        }

        boolean needsResolution = !finalStatus
                && evaluateTerminalPolicy(task, stats).getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL;
        if (projectionAudit) {
            needsResolution = needsResolution || auditTaskMessageProjection(taskId, violations);
        }
        TaskStateValidationResult result = new TaskStateValidationResult(
                violations.isEmpty(),
                needsResolution,
                task.getStatus(),
                task.getTerminalReason(),
                stats.totalCount(),
                stats.successCount(),
                stats.failedCount(),
                stats.processingCount(),
                projectionAudit
                        ? TaskStateValidationResult.Scope.PROJECTION_AUDIT
                        : TaskStateValidationResult.Scope.RUNTIME,
                List.copyOf(violations)
        );
        if (!result.isValid() || result.isNeedsResolution()) {
            emitTaskStateValidationSummary(
                    taskId,
                    result,
                    result.isNeedsResolution()
                            ? "task requires explicit terminal reconciliation"
                            : "task validation found invariant violations",
                    projectionAudit ? "AUDIT_TASK_PROJECTION_STATE" : "VALIDATE_TASK_STATE"
            );
        }
        return result;
    }

    private boolean auditTaskMessageProjection(String taskId,
                                               List<TaskStateValidationResult.ViolationCode> violations) {
        boolean attemptNeedsResolution = false;
        for (com.xa.mass.storage.api.TaskDetailStore.TaskMessageProjection taskMsg : getTaskMessagesForProjectionAudit(taskId)) {
            if (taskMsg == null) {
                continue;
            }
            if (isCompleted(taskMsg) && taskMsg.finalReason() == null) {
                violations.add(TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_MISSING);
            }
            if (isCompleted(taskMsg) && !TaskMessageAttemptSupport.isTaskMessageFinalReasonCompatible(taskMsg)) {
                violations.add(TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_STATUS_MISMATCH);
            }
            CompatibilityAttemptStats attemptStats =
                    getTaskMessageAttemptStats(taskId, taskMsg.messageId());
            long activeAttemptCount = attemptStats.activeAttempts();
            boolean hasActiveAttempt = activeAttemptCount > 0;
            if (activeAttemptCount > 1) {
                violations.add(TaskStateValidationResult.ViolationCode.MULTIPLE_ACTIVE_ATTEMPTS_FOR_MESSAGE);
            }
            if (hasActiveAttempt && isCompleted(taskMsg)) {
                violations.add(TaskStateValidationResult.ViolationCode.ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE);
            }
            boolean allAttemptsFinal = attemptStats.totalAttempts() > 0 && activeAttemptCount == 0;
            if (allAttemptsFinal
                    && !isCompleted(taskMsg)
                    && taskMsg.status() != TaskMsgStatus.INIT) {
                attemptNeedsResolution = true;
                violations.add(TaskStateValidationResult.ViolationCode.ALL_ATTEMPTS_FINAL_BUT_MESSAGE_NOT_FINAL);
            }
        }
        return attemptNeedsResolution;
    }

    private void emitTaskStateValidationSummary(String taskId,
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

    private TaskWorkStats getTaskWorkStats(String taskId) {
        return stateRuntime.getTaskWorkStats(taskId);
    }

    private TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
        return stateRuntime.evaluateTerminalPolicy(task, stats);
    }

    @CompatibilityProjectionOnly
    private java.util.List<com.xa.mass.storage.api.TaskDetailStore.TaskMessageProjection> getTaskMessagesForProjectionAudit(String taskId) {
        return compatibilityProjectionAccess.getTaskMessageProjectionsForAudit(taskId);
    }

    private CompatibilityAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return compatibilityProjectionAccess.getTaskMessageAttemptStats(taskId, messageId);
    }

    private boolean isCompleted(com.xa.mass.storage.api.TaskDetailStore.TaskMessageProjection taskMsg) {
        return taskMsg != null && taskMsg.status() != null && taskMsg.status().isFinal();
    }

}

