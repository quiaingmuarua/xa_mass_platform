package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskTerminalReason;

/**
 * Explicit result returned by {@code TaskTerminalPolicy}.
 */
public class TaskTerminalPolicyDecision {

    public enum Outcome {
        KEEP_RUNNING,
        FINALIZE_TO_TERMINAL
    }

    private final Outcome outcome;
    private final TaskTerminalReason terminalReason;

    private TaskTerminalPolicyDecision(Outcome outcome, TaskTerminalReason terminalReason) {
        this.outcome = outcome;
        this.terminalReason = terminalReason;
    }

    public static TaskTerminalPolicyDecision keepRunning() {
        return new TaskTerminalPolicyDecision(Outcome.KEEP_RUNNING, null);
    }

    public static TaskTerminalPolicyDecision finalizeToTerminal(TaskTerminalReason terminalReason) {
        if (terminalReason == null) {
            throw new IllegalArgumentException("terminalReason is required when finalizing to TERMINAL");
        }
        return new TaskTerminalPolicyDecision(Outcome.FINALIZE_TO_TERMINAL, terminalReason);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public TaskTerminalReason getTerminalReason() {
        return terminalReason;
    }
}
