package com.xa.mass.sdk.model;

import java.time.LocalDateTime;

public final class TaskSummarySnapshot {

    private final String taskId;
    private final String taskName;
    private final String tenantId;
    private final String project;
    private final String userId;
    private final String contract;
    private final String status;
    private final String terminalReason;
    private final TaskExecutionOptions executionSpec;
    private final int taskSuccessNumber;
    private final int taskEligibleNumber;
    private final LocalDateTime updateTime;

    public TaskSummarySnapshot(String taskId,
                               String taskName,
                               String tenantId,
                               String project,
                               String userId,
                               String contract,
                               String status,
                               String terminalReason,
                               TaskExecutionOptions executionSpec,
                               int taskSuccessNumber,
                               int taskEligibleNumber,
                               LocalDateTime updateTime) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.tenantId = tenantId;
        this.project = project;
        this.userId = userId;
        this.contract = contract;
        this.status = status;
        this.terminalReason = terminalReason;
        this.executionSpec = TaskExecutionOptions.normalized(executionSpec);
        this.taskSuccessNumber = taskSuccessNumber;
        this.taskEligibleNumber = taskEligibleNumber;
        this.updateTime = updateTime;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getProject() {
        return project;
    }

    public String getUserId() {
        return userId;
    }

    public String getContract() {
        return contract;
    }

    public String getStatus() {
        return status;
    }

    public String getTerminalReason() {
        return terminalReason;
    }

    public TaskExecutionOptions getExecutionSpec() {
        return executionSpec;
    }

    public int getTaskSuccessNumber() {
        return taskSuccessNumber;
    }

    public int getTaskEligibleNumber() {
        return taskEligibleNumber;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }
}
