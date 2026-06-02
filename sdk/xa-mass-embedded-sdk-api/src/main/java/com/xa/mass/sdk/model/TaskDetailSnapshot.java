package com.xa.mass.sdk.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskDetailSnapshot {

    private final String taskId;
    private final String tenantId;
    private final String taskName;
    private final String contract;
    private final String project;
    private final String status;
    private final int taskTargetNumber;
    private final int taskEligibleNumber;
    private final int taskSuccessNumber;
    private final int taskNonSuccessNumber;
    private final int minRequiredWorkerCount;
    private final int peakAssignedWorkerCount;
    private final Map<String, Object> sharedConfig;
    private final String holdReason;
    private final TaskExecutionOptions executionSpec;
    private final String sourceRef;
    private final String intakeStatus;
    private final String userId;
    private final LocalDateTime createTime;
    private final LocalDateTime updateTime;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final String terminalReason;

    public TaskDetailSnapshot(String taskId,
                              String tenantId,
                              String taskName,
                              String contract,
                              String project,
                              String status,
                              int taskTargetNumber,
                              int taskEligibleNumber,
                              int taskSuccessNumber,
                              int taskNonSuccessNumber,
                              int minRequiredWorkerCount,
                              int peakAssignedWorkerCount,
                              Map<String, Object> sharedConfig,
                              String holdReason,
                              TaskExecutionOptions executionSpec,
                              String sourceRef,
                              String intakeStatus,
                              String userId,
                              LocalDateTime createTime,
                              LocalDateTime updateTime,
                              LocalDateTime startTime,
                              LocalDateTime endTime,
                              String terminalReason) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.taskName = taskName;
        this.contract = contract;
        this.project = project;
        this.status = status;
        this.taskTargetNumber = taskTargetNumber;
        this.taskEligibleNumber = taskEligibleNumber;
        this.taskSuccessNumber = taskSuccessNumber;
        this.taskNonSuccessNumber = taskNonSuccessNumber;
        this.minRequiredWorkerCount = minRequiredWorkerCount;
        this.peakAssignedWorkerCount = peakAssignedWorkerCount;
        this.sharedConfig = copyMap(sharedConfig);
        this.holdReason = holdReason;
        this.executionSpec = TaskExecutionOptions.normalized(executionSpec);
        this.sourceRef = sourceRef;
        this.intakeStatus = intakeStatus;
        this.userId = userId;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.startTime = startTime;
        this.endTime = endTime;
        this.terminalReason = terminalReason;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getContract() {
        return contract;
    }

    public String getProject() {
        return project;
    }

    public String getStatus() {
        return status;
    }

    public int getTaskTargetNumber() {
        return taskTargetNumber;
    }

    public int getTaskEligibleNumber() {
        return taskEligibleNumber;
    }

    public int getTaskSuccessNumber() {
        return taskSuccessNumber;
    }

    public int getTaskNonSuccessNumber() {
        return taskNonSuccessNumber;
    }

    public int getMinRequiredWorkerCount() {
        return minRequiredWorkerCount;
    }

    public int getPeakAssignedWorkerCount() {
        return peakAssignedWorkerCount;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public String getHoldReason() {
        return holdReason;
    }

    public TaskExecutionOptions getExecutionSpec() {
        return executionSpec;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getIntakeStatus() {
        return intakeStatus;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public String getTerminalReason() {
        return terminalReason;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
