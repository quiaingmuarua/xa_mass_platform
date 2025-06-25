package com.xa.mass.engine.monkey.snapshot;

import java.time.LocalDateTime;

/**
 * 任务属性快照
 */
public class TaskSnapshot {
    private String taskId;
    private String taskName;
    private String project;
    private String taskCountry;
    private String taskStatus;
    private int taskInitNumber;
    private int taskValidNumber;
    private int taskExecutedNumber;
    private int taskUnExecutedNumber;
    private int runTaskMinDeviceCnt;
    private int scheduleDeviceCnt;
    private int batchSize;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public TaskSnapshot() {
    }

    // Getters and Setters
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

    public String getTaskCountry() {
        return taskCountry;
    }

    public void setTaskCountry(String taskCountry) {
        this.taskCountry = taskCountry;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public int getTaskInitNumber() {
        return taskInitNumber;
    }

    public void setTaskInitNumber(int taskInitNumber) {
        this.taskInitNumber = taskInitNumber;
    }

    public int getTaskValidNumber() {
        return taskValidNumber;
    }

    public void setTaskValidNumber(int taskValidNumber) {
        this.taskValidNumber = taskValidNumber;
    }

    public int getTaskExecutedNumber() {
        return taskExecutedNumber;
    }

    public void setTaskExecutedNumber(int taskExecutedNumber) {
        this.taskExecutedNumber = taskExecutedNumber;
    }

    public int getTaskUnExecutedNumber() {
        return taskUnExecutedNumber;
    }

    public void setTaskUnExecutedNumber(int taskUnExecutedNumber) {
        this.taskUnExecutedNumber = taskUnExecutedNumber;
    }

    public int getRunTaskMinDeviceCnt() {
        return runTaskMinDeviceCnt;
    }

    public void setRunTaskMinDeviceCnt(int runTaskMinDeviceCnt) {
        this.runTaskMinDeviceCnt = runTaskMinDeviceCnt;
    }

    public int getScheduleDeviceCnt() {
        return scheduleDeviceCnt;
    }

    public void setScheduleDeviceCnt(int scheduleDeviceCnt) {
        this.scheduleDeviceCnt = scheduleDeviceCnt;
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