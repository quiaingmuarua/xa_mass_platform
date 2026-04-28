package com.xa.mass.base.enums.task;

/**
 * Business reason explaining why a task entered TERMINAL.
 */
public enum TaskTerminalReason {
    MANUAL_CANCELLED,
    ALL_MESSAGES_SUCCEEDED,
    ALL_MESSAGES_FAILED,
    MIXED_MESSAGE_RESULTS,
    MAX_RUNTIME_REACHED,
    SUCCESS_RATE_REACHED,
    RETRY_BUDGET_EXHAUSTED;

    /**
     * Reasons that are allowed to close a task even while intake is still OPEN.
     *
     * <p>These are operator- or policy-driven stop conditions rather than
     * normal "all persisted messages finalized" convergence.
     */
    public boolean allowsOpenIntakeClosure() {
        return switch (this) {
            case MANUAL_CANCELLED,
                    MAX_RUNTIME_REACHED,
                    SUCCESS_RATE_REACHED,
                    RETRY_BUDGET_EXHAUSTED -> true;
            default -> false;
        };
    }

    /**
     * Non-message policy closure reasons. These are terminal reasons whose
     * semantics are not derived directly from the current TaskMsg aggregate.
     */
    public boolean isPolicyDrivenStop() {
        return switch (this) {
            case MAX_RUNTIME_REACHED,
                    SUCCESS_RATE_REACHED,
                    RETRY_BUDGET_EXHAUSTED -> true;
            default -> false;
        };
    }
}
