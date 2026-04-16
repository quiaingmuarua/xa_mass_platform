package com.xa.mass.base.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Core task aggregate.
 *
 * <p>The task-level counters are intentionally coarse-grained and explicit:
 * {@code taskTargetNumber} is the initial persisted target count,
 * {@code taskEligibleNumber} is the count currently included in the mainline aggregate,
 * {@code taskSuccessNumber} is the count already in SUCCESS,
 * and {@code taskNonSuccessNumber} is the eligible count that is not yet SUCCESS.
 */
public class Task {
    private String tid;
    private String taskName;
    private String project;
    private TaskStatus status;
    private String taskRoutingCode;
    private int taskTargetNumber;
    private int taskEligibleNumber;
    private int taskSuccessNumber;
    private int taskNonSuccessNumber;
    private int minRequiredWorkerCount;
    private int peakAssignedWorkerCount;
    private Map<String, Object> sharedConfig = new HashMap<>();
    private boolean openEnded = false;
    private User user;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int batchSize;
    private TaskTerminalReason terminalReason;

    public Task() {
        this.status = TaskStatus.NEW;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public Task(String tid, String taskName, String project, String taskRoutingCode,
                int taskTargetNumber, Map<String, Object> sharedConfig, User user) {
        this();
        this.tid = tid;
        this.taskName = taskName;
        this.project = project;
        this.taskRoutingCode = taskRoutingCode;
        this.taskTargetNumber = taskTargetNumber;
        this.taskEligibleNumber = taskTargetNumber;
        this.taskSuccessNumber = 0;
        this.taskNonSuccessNumber = taskTargetNumber;
        this.sharedConfig = sharedConfig;
        this.user = user;
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
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

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }

    public String getTaskRoutingCode() {
        return taskRoutingCode;
    }

    public void setTaskRoutingCode(String taskRoutingCode) {
        this.taskRoutingCode = taskRoutingCode;
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
        recomputeNonSuccessNumber();
    }

    public int getTaskSuccessNumber() {
        return taskSuccessNumber;
    }

    public void setTaskSuccessNumber(int taskSuccessNumber) {
        this.taskSuccessNumber = taskSuccessNumber;
        recomputeNonSuccessNumber();
        this.updateTime = LocalDateTime.now();
    }

    public int getTaskNonSuccessNumber() {
        return taskNonSuccessNumber;
    }

    public void setTaskNonSuccessNumber(int taskNonSuccessNumber) {
        this.taskNonSuccessNumber = taskNonSuccessNumber;
        this.updateTime = LocalDateTime.now();
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
        this.updateTime = LocalDateTime.now();
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public void setSharedConfig(Map<String, Object> sharedConfig) {
        this.sharedConfig = sharedConfig;
    }

    public boolean isOpenEnded() {
        return openEnded;
    }

    public void setOpenEnded(boolean openEnded) {
        this.openEnded = openEnded;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
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

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public int getBatchSize() {
        return Math.max(batchSize, 1);
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public TaskTerminalReason getTerminalReason() {
        return terminalReason;
    }

    public void setTerminalReason(TaskTerminalReason terminalReason) {
        this.terminalReason = terminalReason;
    }

    public boolean isSchedulable() {
        return status.isSchedulable() && taskNonSuccessNumber > 0;
    }

    public boolean isCompleted() {
        return status.isFinal();
    }

    public double getProgressPercentage() {
        if (taskEligibleNumber == 0) {
            return 0.0;
        }
        return (double) taskSuccessNumber / taskEligibleNumber * 100;
    }

    public boolean transitionTo(TaskStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            if (targetStatus == TaskStatus.RUNNING && startTime == null) {
                setStartTime(LocalDateTime.now());
            } else if (targetStatus.isFinal()) {
                setEndTime(LocalDateTime.now());
            }
            return true;
        }
        return false;
    }

    public boolean transitionTo(TaskStatus targetStatus, TaskTerminalReason terminalReason) {
        if (!targetStatus.isFinal()) {
            throw new IllegalArgumentException("Terminal reason is only valid for final task states");
        }
        if (transitionTo(targetStatus)) {
            this.terminalReason = terminalReason;
            return true;
        }
        return false;
    }

    private void recomputeNonSuccessNumber() {
        this.taskNonSuccessNumber = this.taskEligibleNumber - this.taskSuccessNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(tid, task.tid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tid);
    }

    @Override
    public String toString() {
        return "Task{" +
                "tid='" + tid + '\'' +
                ", taskName='" + taskName + '\'' +
                ", project='" + project + '\'' +
                ", status=" + status +
                ", taskRoutingCode='" + taskRoutingCode + '\'' +
                ", taskTargetNumber=" + taskTargetNumber +
                ", taskEligibleNumber=" + taskEligibleNumber +
                ", taskSuccessNumber=" + taskSuccessNumber +
                ", taskNonSuccessNumber=" + taskNonSuccessNumber +
                ", minRequiredWorkerCount=" + minRequiredWorkerCount +
                ", peakAssignedWorkerCount=" + peakAssignedWorkerCount +
                ", progress=" + String.format("%.1f%%", getProgressPercentage()) +
                ", batchSize=" + batchSize +
                ", terminalReason=" + terminalReason +
                '}';
    }
}
