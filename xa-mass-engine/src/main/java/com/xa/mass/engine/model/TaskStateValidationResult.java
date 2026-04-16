package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;

import java.util.List;

/**
 * Result of validating the current Task + TaskMsg aggregate invariants.
 */
public class TaskStateValidationResult {
    public enum ViolationCode {
        TASK_NOT_FOUND,
        NEGATIVE_ELIGIBLE_COUNT,
        NEGATIVE_SUCCESS_COUNT,
        SUCCESS_EXCEEDS_ELIGIBLE,
        NON_SUCCESS_COUNT_MISMATCH,
        TERMINAL_REASON_MISSING,
        TERMINAL_REASON_PRESENT_ON_NON_TERMINAL,
        TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED,
        TERMINAL_REASON_MISMATCH_ALL_FAILED,
        TERMINAL_REASON_MISMATCH_MIXED_RESULTS
    }

    private final boolean valid;
    private final boolean needsResolution;
    private final TaskStatus status;
    private final TaskTerminalReason terminalReason;
    private final long totalMessages;
    private final long successMessages;
    private final long failedMessages;
    private final long processingMessages;
    private final List<ViolationCode> violations;

    public TaskStateValidationResult(
            boolean valid,
            boolean needsResolution,
            TaskStatus status,
            TaskTerminalReason terminalReason,
            long totalMessages,
            long successMessages,
            long failedMessages,
            long processingMessages,
            List<ViolationCode> violations
    ) {
        this.valid = valid;
        this.needsResolution = needsResolution;
        this.status = status;
        this.terminalReason = terminalReason;
        this.totalMessages = totalMessages;
        this.successMessages = successMessages;
        this.failedMessages = failedMessages;
        this.processingMessages = processingMessages;
        this.violations = violations;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isNeedsResolution() {
        return needsResolution;
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

    public long getProcessingMessages() {
        return processingMessages;
    }

    public List<ViolationCode> getViolations() {
        return violations;
    }
}
