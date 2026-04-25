package com.xa.mass.base.model;

import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Task message entity.
 * Records assignment, dispatch, and execution progress for a single work item.
 */
public class TaskMsg {
    private String messageId;
    private String taskId;
    // Compatibility projection of the latest attempt binding for UI/API callers.
    // Runtime execution truth lives in TaskMsgAttempt history.
    private String latestAttemptWorkerId;
    private String latestAttemptWorkerContextId;
    private TaskMsgStatus status;
    // Optional compatibility projection of the latest attempt batch association.
    // The hot-path kernel must remain correct even when this is absent.
    private String latestAttemptBatchId;
    private LocalDateTime assignedTime;
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

    public TaskMsg(String messageId, String taskId, Map<String, Object> input) {
        this();
        this.messageId = messageId;
        this.taskId = taskId;
        this.input = input != null ? new HashMap<>(input) : new HashMap<>();
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getLatestAttemptWorkerId() {
        return latestAttemptWorkerId;
    }

    /**
     * Compatibility setter for the latest-attempt projection.
     *
     * <p>Authoritative execution history lives in TaskMsgAttempt rows.
     */
    public void setLatestAttemptWorkerId(String latestAttemptWorkerId) {
        this.latestAttemptWorkerId = latestAttemptWorkerId;
    }

    public String getLatestAttemptWorkerContextId() {
        return latestAttemptWorkerContextId;
    }

    /**
     * Compatibility setter for the latest-attempt projection.
     *
     * <p>Authoritative execution history lives in TaskMsgAttempt rows.
     */
    public void setLatestAttemptWorkerContextId(String latestAttemptWorkerContextId) {
        this.latestAttemptWorkerContextId = latestAttemptWorkerContextId;
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

    public String getLatestAttemptBatchId() {
        return latestAttemptBatchId;
    }

    /**
     * Optional compatibility setter for the latest-attempt projection.
     *
     * <p>Authoritative execution history lives in TaskMsgAttempt rows.
     */
    public void setLatestAttemptBatchId(String latestAttemptBatchId) {
        this.latestAttemptBatchId = latestAttemptBatchId;
    }

    public LocalDateTime getAssignedTime() {
        return assignedTime;
    }

    public void setAssignedTime(LocalDateTime assignedTime) {
        this.assignedTime = assignedTime;
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

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input == null ? new HashMap<>() : new HashMap<>(input);
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = copyNullableMap(output);
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

    /**
     * Applies the latest-attempt projection used by compatibility UI/API
     * readers. This must not be treated as the execution-history source of
     * truth; callers should use TaskMsgAttempt for that.
     *
     * <p>{@code batchId} is optional observability metadata. The runtime must
     * remain correct when it is {@code null}.
     */
    public void applyLatestAttemptProjection(String workerId, String workerContextId, String batchId) {
        this.latestAttemptWorkerId = workerId;
        this.latestAttemptWorkerContextId = workerContextId;
        this.latestAttemptBatchId = batchId;
    }

    /**
     * Clears the latest-attempt projection after retry reset or when the
     * binding is no longer valid.
     */
    public void clearLatestAttemptProjection() {
        applyLatestAttemptProjection(null, null, null);
        this.assignedTime = null;
    }

    public synchronized void resetForRetry() {
        if (!transitionTo(TaskMsgStatus.INIT)) {
            throw new IllegalStateException(
                    "Cannot reset message " + messageId + " for retry from status " + status
                    + "; only FAILED or EXPIRED messages may be retried");
        }
        // Clear the stale latest-attempt projection so the next assignment does
        // not inherit a dead worker reference.
        clearLatestAttemptProjection();
        this.startTime = null;
        this.completeTime = null;
        this.errorMessage = null;
        this.errorCode = null;
        this.output = null;
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
            this.errorMessage = null;
            this.errorCode = null;
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
            this.output = null;
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
            this.output = null;
            setFinalReason(finalReason);
            return true;
        }
        return false;
    }

    public synchronized boolean cancelBeforeDispatch(String detail) {
        if (status != TaskMsgStatus.INIT) {
            return false;
        }
        setStatus(TaskMsgStatus.FAILED);
        setFinalReason(TaskMsgFinalReason.MANUAL_CANCELLED);
        setErrorMessage(detail);
        this.output = null;
        return true;
    }

    public void forceFinalize(TaskMsgStatus finalStatus, TaskMsgFinalReason finalReason, String detail) {
        if (finalStatus == null || !finalStatus.isFinal()) {
            throw new IllegalArgumentException("finalStatus must be terminal");
        }
        setStatus(finalStatus);
        setFinalReason(finalReason);
        if (finalStatus == TaskMsgStatus.SUCCESS) {
            this.errorMessage = null;
        } else {
            setErrorMessage(detail);
        }
    }

    private Map<String, Object> copyNullableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return source == null ? null : new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
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
        return Objects.equals(messageId, taskMsg.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId);
    }

    @Override
    public String toString() {
        return "TaskMsg{" +
                "messageId='" + messageId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", latestAttemptWorkerId='" + latestAttemptWorkerId + '\'' +
                ", latestAttemptWorkerContextId='" + latestAttemptWorkerContextId + '\'' +
                ", status=" + status +
                ", latestAttemptBatchId='" + latestAttemptBatchId + '\'' +
                ", retryCount=" + retryCount +
                '}';
    }
}
