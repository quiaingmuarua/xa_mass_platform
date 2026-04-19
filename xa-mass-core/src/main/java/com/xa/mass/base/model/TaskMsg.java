package com.xa.mass.base.model;

import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Task message entity.
 * Records assignment, dispatch, and execution progress for a single work item.
 */
public class TaskMsg {
    private String msgId;
    private String taskId;
    private String workerId;
    private String workerContextId;
    private TaskMsgStatus status;
    private String batchId;
    private LocalDateTime assignedTime;
    private String result;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime startTime;
    private LocalDateTime completeTime;
    private int retryCount;
    private int maxRetryCount;
    private String errorMessage;
    private String errorCode;
    private TaskMsgFinalReason finalReason;
    private Map<String, Object> input;
    private Map<String, Object> output;

    public TaskMsg() {
        this.status = TaskMsgStatus.INIT;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.retryCount = 0;
        this.maxRetryCount = 3;
        this.input = new HashMap<>();
    }

    public TaskMsg(String msgId, String taskId, String target) {
        this();
        this.msgId = msgId;
        this.taskId = taskId;
        this.input = new HashMap<>();
        if (target != null) {
            this.input.put("target", target);
        }
    }

    public TaskMsg(String msgId, String taskId, Map<String, Object> input) {
        this();
        this.msgId = msgId;
        this.taskId = taskId;
        this.input = input != null ? new HashMap<>(input) : new HashMap<>();
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getWorkerContextId() {
        return workerContextId;
    }

    public void setWorkerContextId(String workerContextId) {
        this.workerContextId = workerContextId;
    }

    public TaskMsgStatus getStatus() {
        return status;
    }

    /**
     * Direct status setter — for framework deserialization and internal lifecycle hooks only.
     * All external state changes must go through {@link #transitionTo(TaskMsgStatus)},
     * which enforces the state machine guard before delegating here.
     */
    public void setStatus(TaskMsgStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
        if (status != null && !status.isFinal()) {
            this.finalReason = null;
        }

        if (status == TaskMsgStatus.RUNNING && startTime == null) {
            this.startTime = LocalDateTime.now();
        } else if (status != null && status.isFinal()) {
            this.completeTime = LocalDateTime.now();
        }
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public LocalDateTime getAssignedTime() {
        return assignedTime;
    }

    public void setAssignedTime(LocalDateTime assignedTime) {
        this.assignedTime = assignedTime;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
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

    public LocalDateTime getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(LocalDateTime completeTime) {
        this.completeTime = completeTime;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Short symbolic error code set by the worker (e.g. "RATE_LIMITED", "CAPTCHA").
     * Lets the orchestrator and callers branch on error type without parsing errorMessage.
     */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public TaskMsgFinalReason getFinalReason() {
        return finalReason;
    }

    public void setFinalReason(TaskMsgFinalReason finalReason) {
        this.finalReason = finalReason;
    }

    /** Compatibility accessor: returns input.get("target") cast to String. */
    public String getTarget() {
        return input != null ? (String) input.get("target") : null;
    }

    /** Compatibility accessor: sets "target" key in input map. */
    public void setTarget(String target) {
        if (input == null) {
            input = new HashMap<>();
        }
        input.put("target", target);
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }

    public boolean isCompleted() {
        return status != null && status.isFinal();
    }

    public boolean isSuccess() {
        return status != null && status.isSuccess();
    }

    public boolean isFailed() {
        return status != null && status.isFailed();
    }

    public boolean isProcessing() {
        return status != null && status.isProcessing();
    }

    public boolean canRetry() {
        return status != null && status.isRetryable() && retryCount < maxRetryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.updateTime = LocalDateTime.now();
    }

    public synchronized void resetForRetry() {
        if (!transitionTo(TaskMsgStatus.INIT)) {
            throw new IllegalStateException(
                    "Cannot reset msg " + msgId + " for retry from status " + status
                    + "; only FAILED or EXPIRED messages may be retried");
        }
        // Clear the stale binding left by the previous attempt so the next
        // assignment does not inherit a dead worker reference.
        this.workerId = null;
        this.workerContextId = null;
        this.batchId = null;
        this.assignedTime = null;
        this.startTime = null;
        this.completeTime = null;
        this.errorMessage = null;
        this.errorCode = null;
        this.result = null;
        // finalReason is already cleared by setStatus(INIT) side-effect
    }

    public long getExecutionDuration() {
        if (startTime == null) {
            return 0;
        }
        LocalDateTime endTime = completeTime != null ? completeTime : LocalDateTime.now();
        return java.time.Duration.between(startTime, endTime).toMillis();
    }

    public synchronized boolean transitionTo(TaskMsgStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            return true;
        }
        return false;
    }

    public boolean markAsAssigned() {
        if (transitionTo(TaskMsgStatus.ASSIGNED)) {
            setAssignedTime(LocalDateTime.now());
            return true;
        }
        return false;
    }

    public boolean markAsRunning() {
        return transitionTo(TaskMsgStatus.RUNNING);
    }

    public boolean markAsSuccess(String result) {
        return markAsSuccess(result, TaskMsgFinalReason.BUSINESS_SUCCESS);
    }

    public boolean markAsSuccess(String result, TaskMsgFinalReason finalReason) {
        if (transitionTo(TaskMsgStatus.SUCCESS)) {
            setResult(result);
            setFinalReason(finalReason);
            return true;
        }
        return false;
    }

    public boolean markAsFailed(String errorMessage) {
        return markAsFailed(errorMessage, TaskMsgFinalReason.BUSINESS_FAILED);
    }

    public boolean markAsFailed(String errorMessage, TaskMsgFinalReason finalReason) {
        if (transitionTo(TaskMsgStatus.FAILED)) {
            setErrorMessage(errorMessage);
            setFinalReason(finalReason);
            return true;
        }
        return false;
    }

    public boolean markAsExpired() {
        return markAsExpired(TaskMsgFinalReason.LEASE_EXPIRED);
    }

    public boolean markAsExpired(TaskMsgFinalReason finalReason) {
        if (transitionTo(TaskMsgStatus.EXPIRED)) {
            setFinalReason(finalReason);
            return true;
        }
        return false;
    }

    public void forceFinalize(TaskMsgStatus finalStatus, TaskMsgFinalReason finalReason, String detail) {
        if (finalStatus == null || !finalStatus.isFinal()) {
            throw new IllegalArgumentException("finalStatus must be terminal");
        }
        setStatus(finalStatus);
        setFinalReason(finalReason);
        if (finalStatus == TaskMsgStatus.SUCCESS) {
            setResult(detail);
        } else {
            setErrorMessage(detail);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskMsg taskMsg = (TaskMsg) o;
        return Objects.equals(msgId, taskMsg.msgId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(msgId);
    }

    @Override
    public String toString() {
        return "TaskMsg{" +
                "msgId='" + msgId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", workerId='" + workerId + '\'' +
                ", workerContextId='" + workerContextId + '\'' +
                ", status=" + status +
                ", batchId='" + batchId + '\'' +
                ", retryCount=" + retryCount +
                ", result='" + result + '\'' +
                '}';
    }
}
