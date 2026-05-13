package com.xa.mass.sdk.model;

import java.util.Objects;

/**
 * Stable SDK task-command outcome snapshot.
 */
public final class TaskCommandResult {

    private final String taskId;
    private final String command;
    private final boolean accepted;
    private final boolean taskExists;
    private final String status;
    private final String intakeStatus;
    private final String terminalReason;
    private final String holdReason;
    private final String failureReason;
    private final String reasonCode;

    public TaskCommandResult(String taskId,
                             String command,
                             boolean accepted,
                             boolean taskExists,
                             String status,
                             String intakeStatus,
                             String terminalReason,
                             String holdReason,
                             String failureReason,
                             String reasonCode) {
        this.taskId = taskId;
        this.command = command;
        this.accepted = accepted;
        this.taskExists = taskExists;
        this.status = status;
        this.intakeStatus = intakeStatus;
        this.terminalReason = terminalReason;
        this.holdReason = holdReason;
        this.failureReason = failureReason;
        this.reasonCode = reasonCode;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getCommand() {
        return command;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public boolean isTaskExists() {
        return taskExists;
    }

    public String getStatus() {
        return status;
    }

    public String getIntakeStatus() {
        return intakeStatus;
    }

    public String getTerminalReason() {
        return terminalReason;
    }

    public String getHoldReason() {
        return holdReason;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskCommandResult that)) return false;
        return accepted == that.accepted
                && taskExists == that.taskExists
                && Objects.equals(taskId, that.taskId)
                && Objects.equals(command, that.command)
                && Objects.equals(status, that.status)
                && Objects.equals(intakeStatus, that.intakeStatus)
                && Objects.equals(terminalReason, that.terminalReason)
                && Objects.equals(holdReason, that.holdReason)
                && Objects.equals(failureReason, that.failureReason)
                && Objects.equals(reasonCode, that.reasonCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, command, accepted, taskExists, status, intakeStatus,
                terminalReason, holdReason, failureReason, reasonCode);
    }

    @Override
    public String toString() {
        return "TaskCommandResult{"
                + "taskId='" + taskId + '\''
                + ", command='" + command + '\''
                + ", accepted=" + accepted
                + ", taskExists=" + taskExists
                + ", status='" + status + '\''
                + ", intakeStatus='" + intakeStatus + '\''
                + ", terminalReason='" + terminalReason + '\''
                + ", holdReason='" + holdReason + '\''
                + ", failureReason='" + failureReason + '\''
                + ", reasonCode='" + reasonCode + '\''
                + '}';
    }
}
