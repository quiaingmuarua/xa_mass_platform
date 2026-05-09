package com.xa.mass.base.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIngestStatus;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskSourceType;
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
    private String tenantId;
    private String taskName;
    private ProjectRef project;
    private TaskStatus status;
    private int taskTargetNumber;
    private int taskEligibleNumber;
    private int taskSuccessNumber;
    private int taskNonSuccessNumber;
    private int minRequiredWorkerCount;
    private int peakAssignedWorkerCount;
    private Map<String, Object> sharedConfig = new HashMap<>();
    private TaskHoldReason holdReason;
    private TaskSourceType sourceType;
    private TaskExecutionSpec executionSpec;
    private TaskIngestStatus ingestStatus;
    private String sourceRef;
    private TaskIntakeStatus intakeStatus;
    private UserRef user;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private TaskTerminalReason terminalReason;

    public Task() {
        this.status = TaskStatus.NEW;
        this.sourceType = TaskSourceType.BATCH;
        this.executionSpec = new TaskExecutionSpec();
        this.ingestStatus = TaskIngestStatus.SEALED;
        this.intakeStatus = TaskIntakeStatus.SEALED;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public Task(String tid, String taskName, String project,
                int taskTargetNumber, Map<String, Object> sharedConfig, UserRef user) {
        this();
        this.tid = tid;
        this.taskName = taskName;
        setProject(project);
        this.taskTargetNumber = taskTargetNumber;
        this.taskEligibleNumber = taskTargetNumber;
        this.taskSuccessNumber = 0;
        this.taskNonSuccessNumber = taskTargetNumber;
        setSharedConfig(sharedConfig);
        this.user = user;
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getProject() {
        return project == null ? null : project.getCode();
    }

    public void setProject(String project) {
        this.project = project == null ? null : ProjectRef.require(project);
    }

    @JsonIgnore
    public ProjectRef getProjectRef() {
        return project;
    }

    public void setProjectRef(ProjectRef project) {
        this.project = project == null ? null : ProjectRef.require(project.getCode());
    }

    public TaskStatus getStatus() {
        return status;
    }

    /**
     * Direct status setter for framework deserialization only.
     * All business state changes must go through {@link #transitionTo(TaskStatus)},
     * which enforces the state machine guard and lifecycle hooks.
     */
    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
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

    /**
     * taskNonSuccessNumber is always derived as (taskEligibleNumber - taskSuccessNumber).
     * This setter is kept for framework deserialization compatibility but ignores the supplied
     * value and recomputes instead, so it cannot break the invariant.
     * To change the value, update taskEligibleNumber or taskSuccessNumber.
     */
    public void setTaskNonSuccessNumber(int taskNonSuccessNumber) {
        recomputeNonSuccessNumber();
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
        this.sharedConfig = sharedConfig == null ? new HashMap<>() : new HashMap<>(sharedConfig);
    }

    public TaskHoldReason getHoldReason() {
        return holdReason;
    }

    public void setHoldReason(TaskHoldReason holdReason) {
        this.holdReason = holdReason;
        this.updateTime = LocalDateTime.now();
    }

    public TaskSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(TaskSourceType sourceType) {
        this.sourceType = sourceType == null ? TaskSourceType.BATCH : sourceType;
        this.updateTime = LocalDateTime.now();
    }

    public TaskExecutionSpec getExecutionSpec() {
        return executionSpec;
    }

    public void setExecutionSpec(TaskExecutionSpec executionSpec) {
        this.executionSpec = TaskExecutionSpec.normalized(executionSpec);
        this.updateTime = LocalDateTime.now();
    }

    public TaskContract getContract() {
        return executionSpecOrDefault().getContract();
    }

    public void setContract(TaskContract contract) {
        executionSpecOrDefault().setContract(contract);
        this.updateTime = LocalDateTime.now();
    }

    public com.xa.mass.base.enums.task.TaskWorkloadClass getWorkloadClass() {
        return executionSpecOrDefault().getWorkloadClass();
    }

    public void setWorkloadClass(com.xa.mass.base.enums.task.TaskWorkloadClass workloadClass) {
        executionSpecOrDefault().setWorkloadClass(workloadClass);
        this.updateTime = LocalDateTime.now();
    }

    public TaskIngestStatus getIngestStatus() {
        return ingestStatus;
    }

    public void setIngestStatus(TaskIngestStatus ingestStatus) {
        this.ingestStatus = ingestStatus == null ? TaskIngestStatus.SEALED : ingestStatus;
        this.updateTime = LocalDateTime.now();
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
        this.updateTime = LocalDateTime.now();
    }

    public TaskIntakeStatus getIntakeStatus() {
        return intakeStatus;
    }

    public void setIntakeStatus(TaskIntakeStatus intakeStatus) {
        this.intakeStatus = intakeStatus == null ? TaskIntakeStatus.SEALED : intakeStatus;
        this.updateTime = LocalDateTime.now();
    }

    public UserRef getUser() {
        return user;
    }

    public void setUser(UserRef user) {
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
        return executionSpecOrDefault().getBatchSize();
    }

    public void setBatchSize(int batchSize) {
        executionSpecOrDefault().setBatchSize(batchSize);
        this.updateTime = LocalDateTime.now();
    }

    public TaskTerminalReason getTerminalReason() {
        return terminalReason;
    }

    public void setTerminalReason(TaskTerminalReason terminalReason) {
        this.terminalReason = terminalReason;
    }

    public int getMaxRuntimeSeconds() {
        return executionSpecOrDefault().getMaxRuntimeSeconds();
    }

    public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
        executionSpecOrDefault().setMaxRuntimeSeconds(maxRuntimeSeconds);
    }

    public int getDefaultMaxRetryCount() {
        return executionSpecOrDefault().getDefaultMaxRetryCount();
    }

    public void setDefaultMaxRetryCount(int defaultMaxRetryCount) {
        executionSpecOrDefault().setDefaultMaxRetryCount(defaultMaxRetryCount);
    }

    @JsonIgnore
    public String getDisplayStatusLabel() {
        if (status == null) {
            return "-";
        }
        if (!status.isFinal()) {
            return status.getDescription();
        }
        if (terminalReason == null) {
            return status.getDescription();
        }
        return switch (terminalReason) {
            case MANUAL_CANCELLED -> "Cancelled";
            case ALL_MESSAGES_SUCCEEDED -> "Completed";
            case ALL_MESSAGES_FAILED -> "Completed (Failed)";
            case MIXED_MESSAGE_RESULTS -> "Completed (Mixed)";
            case MAX_RUNTIME_REACHED -> "Stopped (Max Runtime)";
            case SUCCESS_RATE_REACHED -> "Completed (Target Reached)";
            case RETRY_BUDGET_EXHAUSTED -> "Stopped (Retry Exhausted)";
        };
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

    public synchronized boolean transitionTo(TaskStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            if (targetStatus != TaskStatus.BLOCKED) {
                this.holdReason = null;
            }
            if (targetStatus == TaskStatus.RUNNING && startTime == null) {
                setStartTime(LocalDateTime.now());
            } else if (targetStatus.isFinal()) {
                setEndTime(LocalDateTime.now());
            }
            return true;
        }
        return false;
    }

    public synchronized boolean transitionToBlocked(TaskHoldReason holdReason) {
        if (holdReason == null) {
            throw new IllegalArgumentException("holdReason is required for BLOCKED task state");
        }
        if (status.canTransitionTo(TaskStatus.BLOCKED)) {
            setStatus(TaskStatus.BLOCKED);
            this.holdReason = holdReason;
            return true;
        }
        return false;
    }

    public synchronized boolean transitionTo(TaskStatus targetStatus, TaskTerminalReason terminalReason) {
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

    private TaskExecutionSpec executionSpecOrDefault() {
        if (this.executionSpec == null) {
            this.executionSpec = new TaskExecutionSpec();
        }
        return this.executionSpec;
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
                ", tenantId='" + tenantId + '\'' +
                ", taskName='" + taskName + '\'' +
                ", project='" + project + '\'' +
                ", status=" + status +
                ", sourceType=" + sourceType +
                ", executionSpec=" + executionSpec +
                ", ingestStatus=" + ingestStatus +
                ", taskTargetNumber=" + taskTargetNumber +
                ", taskEligibleNumber=" + taskEligibleNumber +
                ", taskSuccessNumber=" + taskSuccessNumber +
                ", taskNonSuccessNumber=" + taskNonSuccessNumber +
                ", minRequiredWorkerCount=" + minRequiredWorkerCount +
                ", peakAssignedWorkerCount=" + peakAssignedWorkerCount +
                ", progress=" + String.format("%.1f%%", getProgressPercentage()) +
                ", terminalReason=" + terminalReason +
                '}';
    }
}
