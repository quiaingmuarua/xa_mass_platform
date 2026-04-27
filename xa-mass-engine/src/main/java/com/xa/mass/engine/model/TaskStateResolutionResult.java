package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;

/**
 * Result of explicitly resolving task convergence from runtime-owned work
 * stats plus persisted task aggregate state.
 */
public class TaskStateResolutionResult {
    public enum Outcome {
        TASK_NOT_FOUND,
        NOT_FINALIZED,
        FINALIZED_TO_TERMINAL,
        ALREADY_FINAL
    }

    private final Outcome outcome;
    private final TaskStatus status;
    private final TaskTerminalReason terminalReason;
    private final long totalMessages;
    private final long successMessages;
    private final long failedMessages;

    private TaskStateResolutionResult(
            Outcome outcome,
            TaskStatus status,
            TaskTerminalReason terminalReason,
            long totalMessages,
            long successMessages,
            long failedMessages
    ) {
        this.outcome = outcome;
        this.status = status;
        this.terminalReason = terminalReason;
        this.totalMessages = totalMessages;
        this.successMessages = successMessages;
        this.failedMessages = failedMessages;
    }

    public static TaskStateResolutionResult taskNotFound() {
        return new TaskStateResolutionResult(Outcome.TASK_NOT_FOUND, null, null, 0, 0, 0);
    }

    public static TaskStateResolutionResult notFinalized(TaskStatus status, long total, long success, long failed) {
        return new TaskStateResolutionResult(Outcome.NOT_FINALIZED, status, null, total, success, failed);
    }

    public static TaskStateResolutionResult finalizedToTerminal(TaskTerminalReason reason, long total, long success, long failed) {
        return new TaskStateResolutionResult(Outcome.FINALIZED_TO_TERMINAL, TaskStatus.TERMINAL, reason, total, success, failed);
    }

    public static TaskStateResolutionResult alreadyFinal(TaskStatus status, TaskTerminalReason reason, long total, long success, long failed) {
        return new TaskStateResolutionResult(Outcome.ALREADY_FINAL, status, reason, total, success, failed);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public TaskTerminalReason getTerminalReason() {
        return terminalReason;
    }

    public long getTotalMessages() {
        return totalMessages;
    }

    public long getSuccessMessages() {
        return successMessages;
    }

    public long getFailedMessages() {
        return failedMessages;
    }
}

