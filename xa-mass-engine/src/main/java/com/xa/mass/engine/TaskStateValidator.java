package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.util.TraceEventLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Audits persisted task and task-message state for invariant violations and reconciliation needs.
 */
class TaskStateValidator {

    private final TaskManager taskManager;

    TaskStateValidator(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    TaskStateValidationResult validateTaskState(String taskId) {
        Task task = taskManager.getTask(taskId);
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
                    List.of(TaskStateValidationResult.ViolationCode.TASK_NOT_FOUND)
            );
            emitTaskStateValidationSummary(taskId, result, "task not found");
            return result;
        }

        TaskStorage.TaskMessageStats stats = taskManager.getTaskMessageStats(taskId);
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
                    if (!(stats.getTotal() > 0 && stats.getSuccess() == stats.getTotal() && stats.getFailed() == 0 && stats.getExpired() == 0 && stats.getProcessing() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED);
                    }
                }
                case ALL_MESSAGES_FAILED -> {
                    if (!(stats.getTotal() > 0 && stats.getFailed() + stats.getExpired() == stats.getTotal() && stats.getSuccess() == 0 && stats.getProcessing() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_FAILED);
                    }
                }
                case MIXED_MESSAGE_RESULTS -> {
                    boolean mixed = stats.getTotal() > 0
                            && stats.getSuccess() > 0
                            && stats.getFailed() + stats.getExpired() > 0
                            && stats.getSuccess() + stats.getFailed() + stats.getExpired() == stats.getTotal()
                            && stats.getProcessing() == 0;
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

        boolean attemptNeedsResolution = false;
        for (TaskMsg taskMsg : taskManager.getTaskMessages(taskId)) {
            if (taskMsg == null) {
                continue;
            }
            if (taskMsg.isCompleted() && taskMsg.getFinalReason() == null) {
                violations.add(TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_MISSING);
            }
            if (taskMsg.isCompleted() && !TaskMessageAttemptSupport.isTaskMsgFinalReasonCompatible(taskMsg)) {
                violations.add(TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_STATUS_MISMATCH);
            }
            List<TaskMsgAttempt> attempts = taskManager.getTaskMessageAttempts(taskId, taskMsg.getMsgId());
            long activeAttemptCount = attempts.stream()
                    .filter(attempt -> attempt.getStatus() != null && attempt.getStatus().isActive())
                    .count();
            boolean hasActiveAttempt = activeAttemptCount > 0;
            if (activeAttemptCount > 1) {
                violations.add(TaskStateValidationResult.ViolationCode.MULTIPLE_ACTIVE_ATTEMPTS_FOR_MESSAGE);
            }
            if (hasActiveAttempt && taskMsg.isCompleted()) {
                violations.add(TaskStateValidationResult.ViolationCode.ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE);
            }
            if (!attempts.isEmpty()
                    && attempts.stream().allMatch(TaskMsgAttempt::isFinal)
                    && !taskMsg.isCompleted()
                    && taskMsg.getStatus() != TaskMsgStatus.INIT) {
                attemptNeedsResolution = true;
                violations.add(TaskStateValidationResult.ViolationCode.ALL_ATTEMPTS_FINAL_BUT_MESSAGE_NOT_FINAL);
            }
        }

        boolean needsResolution = !finalStatus
                && taskManager.getTaskTerminalPolicy().evaluate(task, stats).getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL;
        needsResolution = needsResolution || attemptNeedsResolution;
        TaskStateValidationResult result = new TaskStateValidationResult(
                violations.isEmpty(),
                needsResolution,
                task.getStatus(),
                task.getTerminalReason(),
                stats.getTotal(),
                stats.getSuccess(),
                stats.getFailed(),
                stats.getProcessing(),
                List.copyOf(violations)
        );
        if (!result.isValid() || result.isNeedsResolution()) {
            emitTaskStateValidationSummary(taskId, result,
                    result.isNeedsResolution()
                            ? "task requires explicit terminal reconciliation"
                            : "task validation found invariant violations");
        }
        return result;
    }

    private void emitTaskStateValidationSummary(String taskId,
                                                TaskStateValidationResult validationResult,
                                                String reason) {
        String violationSummary = validationResult.getViolations().stream()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        TraceEventLogger.taskStateValidationSummary(
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
                "VALIDATE_TASK_STATE",
                "TaskManager",
                reason,
                validationResult.isValid() && !validationResult.isNeedsResolution() ? "SUCCESS" : "ANOMALY"
        );
    }
}
