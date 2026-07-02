package com.xa.mass.sdk.model;

public final class TaskStateResolutionSnapshot {

    private final String outcome;
    private final String status;
    private final String terminalReason;
    private final long totalMessages;
    private final long successMessages;
    private final long failedMessages;

    public TaskStateResolutionSnapshot(
            String outcome,
            String status,
            String terminalReason,
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

    public String getOutcome() {
        return outcome;
    }

    public String getStatus() {
        return status;
    }

    public String getTerminalReason() {
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
