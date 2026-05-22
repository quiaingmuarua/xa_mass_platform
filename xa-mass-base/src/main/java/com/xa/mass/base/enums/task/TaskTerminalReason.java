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
     * Non-message policy closure reasons. These are terminal reasons whose
     * semantics are not derived directly from the current compatibility
     * message projection.
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
