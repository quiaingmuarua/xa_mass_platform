package com.xa.mass.sdk.model;

public final class TaskStateSnapshot {

    private final String taskId;
    private final String status;
    private final String terminalReason;
    private final String intakeStatus;

    public TaskStateSnapshot(String taskId, String status, String terminalReason, String intakeStatus) {
        this.taskId = taskId;
        this.status = status;
        this.terminalReason = terminalReason;
        this.intakeStatus = intakeStatus;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getStatus() {
        return status;
    }

    public String getTerminalReason() {
        return terminalReason;
    }

    public String getIntakeStatus() {
        return intakeStatus;
    }
}
