package com.xa.mass.sdk.model;

import java.util.List;

public final class TaskStateValidationSnapshot {

    private final boolean valid;
    private final boolean needsResolution;
    private final String status;
    private final String terminalReason;
    private final long totalMessages;
    private final long successMessages;
    private final long failedMessages;
    private final long processingMessages;
    private final String scope;
    private final List<String> violations;

    public TaskStateValidationSnapshot(
            boolean valid,
            boolean needsResolution,
            String status,
            String terminalReason,
            long totalMessages,
            long successMessages,
            long failedMessages,
            long processingMessages,
            String scope,
            List<String> violations
    ) {
        this.valid = valid;
        this.needsResolution = needsResolution;
        this.status = status;
        this.terminalReason = terminalReason;
        this.totalMessages = totalMessages;
        this.successMessages = successMessages;
        this.failedMessages = failedMessages;
        this.processingMessages = processingMessages;
        this.scope = scope;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isNeedsResolution() {
        return needsResolution;
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

    public long getProcessingMessages() {
        return processingMessages;
    }

    public String getScope() {
        return scope;
    }

    public List<String> getViolations() {
        return violations;
    }
}
