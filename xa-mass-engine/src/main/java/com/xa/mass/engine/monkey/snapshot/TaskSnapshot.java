package com.xa.mass.engine.monkey.snapshot;

import java.time.LocalDateTime;

/**
 * Task snapshot payload used for assignment diagnostics.
 */
public class TaskSnapshot {
    private String taskId;
    private String taskName;
    private String project;
    private String routingCode;
    private String taskStatus;
    private int taskTargetNumber;
    private int taskEligibleNumber;
    private int taskSuccessNumber;
    private int taskNonSuccessNumber;
    private int minRequiredWorkerCount;
    private int peakAssignedWorkerCount;
    private int batchSize;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getRoutingCode() {
        return routingCode;
    }

    public void setRoutingCode(String routingCode) {
        this.routingCode = routingCode;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public int getTaskTargetNumber() {
        return taskTargetNumber;
    }

    public void setTaskTargetNumber(int taskTargetNumber) {
        this.taskTargetNumber = taskTargetNumber;
    }

    public int getTaskEligibleNumber() {
        return taskEligibleNumber;
    }

    public void setTaskEligibleNumber(int taskEligibleNumber) {
        this.taskEligibleNumber = taskEligibleNumber;
    }

    public int getTaskSuccessNumber() {
        return taskSuccessNumber;
    }

    public void setTaskSuccessNumber(int taskSuccessNumber) {
        this.taskSuccessNumber = taskSuccessNumber;
    }

    public int getTaskNonSuccessNumber() {
        return taskNonSuccessNumber;
    }

    public void setTaskNonSuccessNumber(int taskNonSuccessNumber) {
        this.taskNonSuccessNumber = taskNonSuccessNumber;
    }

    public int getMinRequiredWorkerCount() {
        return minRequiredWorkerCount;
    }

    public void setMinRequiredWorkerCount(int minRequiredWorkerCount) {
        this.minRequiredWorkerCount = minRequiredWorkerCount;
    }

    public int getPeakAssignedWorkerCount() {
        return peakAssignedWorkerCount;
    }

    public void setPeakAssignedWorkerCount(int peakAssignedWorkerCount) {
        this.peakAssignedWorkerCount = peakAssignedWorkerCount;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
