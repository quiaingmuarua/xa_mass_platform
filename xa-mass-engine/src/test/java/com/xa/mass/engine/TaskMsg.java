package com.xa.mass.engine;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class TaskMsg {
    private String messageId;
    private String taskId;
    private Map<String, Object> input = new HashMap<>();
    private String payloadRef;
    private TaskMsgStatus status = TaskMsgStatus.INIT;
    private LocalDateTime assignedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime startTime;
    private LocalDateTime completeTime;
    private int retryCount;
    private int maxRetryCount = 3;
    private String errorMessage;
    private String errorCode;
    private TaskMsgFinalReason finalReason;
    private Map<String, Object> output;
    private String latestAttemptId;
    private String latestAttemptWorkerId;
    private String latestAttemptWorkerContextId;
    private String latestAttemptBatchId;

    public TaskMsg() {
    }

    public TaskMsg(String messageId, String taskId, Map<String, Object> input) {
        this(messageId, taskId, input, null);
    }

    public TaskMsg(String messageId, String taskId, String payloadRef) {
        this(messageId, taskId, Map.of(), payloadRef);
    }

    public TaskMsg(String messageId, String taskId, Map<String, Object> input, String payloadRef) {
        this.messageId = messageId;
        this.taskId = taskId;
        setInput(input);
        this.payloadRef = payloadRef;
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> input) { this.input = input == null ? new HashMap<>() : new HashMap<>(input); }
    public String getPayloadRef() { return payloadRef; }
    public void setPayloadRef(String payloadRef) { this.payloadRef = payloadRef; }
    public TaskMsgStatus getStatus() { return status; }
    public void setStatus(TaskMsgStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
        if (status == TaskMsgStatus.INIT) {
            this.finalReason = null;
        }
        if (status == TaskMsgStatus.RUNNING && startTime == null) {
            this.startTime = LocalDateTime.now();
        } else if (status != null && status.isFinal()) {
            this.completeTime = LocalDateTime.now();
        }
    }
    public LocalDateTime getAssignedTime() { return assignedTime; }
    public void setAssignedTime(LocalDateTime assignedTime) { this.assignedTime = assignedTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getCompleteTime() { return completeTime; }
    public void setCompleteTime(LocalDateTime completeTime) { this.completeTime = completeTime; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetryCount() { return maxRetryCount; }
    public void setMaxRetryCount(int maxRetryCount) { this.maxRetryCount = maxRetryCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public TaskMsgFinalReason getFinalReason() { return finalReason; }
    public void setFinalReason(TaskMsgFinalReason finalReason) { this.finalReason = finalReason; }
    public Map<String, Object> getOutput() { return output; }
    public void setOutput(Map<String, Object> output) { this.output = copyNullableMap(output); }
    public String latestAttemptId() { return latestAttemptId; }
    public String getLatestAttemptWorkerId() { return latestAttemptWorkerId; }
    public void setLatestAttemptWorkerId(String latestAttemptWorkerId) { this.latestAttemptWorkerId = latestAttemptWorkerId; }
    public String getLatestAttemptWorkerContextId() { return latestAttemptWorkerContextId; }
    public void setLatestAttemptWorkerContextId(String latestAttemptWorkerContextId) { this.latestAttemptWorkerContextId = latestAttemptWorkerContextId; }
    public String getLatestAttemptBatchId() { return latestAttemptBatchId; }
    public void setLatestAttemptBatchId(String latestAttemptBatchId) { this.latestAttemptBatchId = latestAttemptBatchId; }
    public boolean isCompleted() { return status != null && status.isFinal(); }
    public boolean isSuccess() { return status != null && status.isSuccess(); }
    public boolean isFailed() { return status != null && status.isFailed(); }
    public boolean isProcessing() { return status != null && status.isProcessing(); }
    public boolean canRetry() { return status != null && status.isRetryable() && retryCount < maxRetryCount; }
    public void incrementRetryCount() { retryCount++; updateTime = LocalDateTime.now(); }

    public void applyLatestAttemptProjection(String workerId, String workerContextId, String batchId) {
        applyLatestAttemptProjection(null, workerId, workerContextId, batchId);
    }

    public void applyLatestAttemptProjection(String attemptId, String workerId, String workerContextId, String batchId) {
        this.latestAttemptId = attemptId;
        this.latestAttemptWorkerId = workerId;
        this.latestAttemptWorkerContextId = workerContextId;
        this.latestAttemptBatchId = batchId;
    }

    public void clearLatestAttemptProjection() {
        applyLatestAttemptProjection(null, null, null, null);
        assignedTime = null;
    }

    public synchronized void resetForRetry() {
        if (!transitionTo(TaskMsgStatus.INIT)) {
            throw new IllegalStateException("Cannot reset message for retry from status " + status);
        }
        clearLatestAttemptProjection();
        startTime = null;
        completeTime = null;
        errorMessage = null;
        errorCode = null;
        output = null;
    }

    public synchronized boolean transitionTo(TaskMsgStatus targetStatus) {
        if (status == null) {
            status = TaskMsgStatus.INIT;
        }
        if (!status.canTransitionTo(targetStatus)) {
            return false;
        }
        setStatus(targetStatus);
        return true;
    }

    public boolean markAsAssigned() {
        boolean changed = transitionTo(TaskMsgStatus.ASSIGNED);
        if (changed && assignedTime == null) {
            assignedTime = LocalDateTime.now();
        }
        return changed;
    }

    public boolean markAsRunning() {
        return transitionTo(TaskMsgStatus.RUNNING);
    }

    public boolean markAsSuccess(String result) {
        return markAsSuccess(result, TaskMsgFinalReason.BUSINESS_SUCCESS);
    }

    public boolean markAsSuccess(String result, TaskMsgFinalReason finalReason) {
        if (!transitionTo(TaskMsgStatus.SUCCESS)) {
            return false;
        }
        this.finalReason = Objects.requireNonNull(finalReason, "finalReason");
        this.output = result == null ? null : Map.of("result", result);
        this.errorMessage = null;
        this.errorCode = null;
        return true;
    }

    public boolean markAsFailed(String detail, TaskMsgFinalReason finalReason) {
        if (!transitionTo(TaskMsgStatus.FAILED)) {
            return false;
        }
        this.finalReason = Objects.requireNonNull(finalReason, "finalReason");
        this.errorMessage = detail;
        return true;
    }

    public boolean markAsExpired() {
        return markAsExpired(TaskMsgFinalReason.LEASE_EXPIRED);
    }

    public boolean markAsExpired(TaskMsgFinalReason finalReason) {
        if (!transitionTo(TaskMsgStatus.EXPIRED)) {
            return false;
        }
        this.finalReason = Objects.requireNonNull(finalReason, "finalReason");
        return true;
    }

    public void forceFinalize(TaskMsgStatus finalStatus, TaskMsgFinalReason finalReason, String detail) {
        this.status = finalStatus;
        this.finalReason = finalReason;
        this.errorMessage = detail;
        this.completeTime = LocalDateTime.now();
        this.updateTime = this.completeTime;
        if (finalStatus == TaskMsgStatus.RUNNING && startTime == null) {
            startTime = updateTime;
        }
    }

    public static TaskMsg fromStorageProjection(TaskDetailStore.TaskMessageProjection projection) {
        if (projection == null) {
            return null;
        }
        TaskMsg taskMsg = projection.payloadRef() == null || projection.payloadRef().isBlank()
                ? new TaskMsg(projection.messageId(), projection.taskId(), projection.input())
                : new TaskMsg(projection.messageId(), projection.taskId(), projection.input(), projection.payloadRef());
        taskMsg.setStatus(projection.status() != null ? TaskMsgStatus.valueOf(projection.status().name()) : null);
        taskMsg.applyLatestAttemptProjection(
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptWorkerContextId(),
                projection.latestAttemptBatchId()
        );
        taskMsg.setAssignedTime(projection.assignedTime());
        taskMsg.setCreateTime(projection.createTime());
        taskMsg.setUpdateTime(projection.updateTime());
        taskMsg.setStartTime(projection.startTime());
        taskMsg.setCompleteTime(projection.completeTime());
        taskMsg.setRetryCount(projection.retryCount());
        taskMsg.setMaxRetryCount(projection.maxRetryCount());
        taskMsg.setErrorMessage(projection.errorMessage());
        taskMsg.setErrorCode(projection.errorCode());
        taskMsg.setFinalReason(projection.finalReason() != null
                ? TaskMsgFinalReason.valueOf(projection.finalReason().name())
                : null);
        taskMsg.setOutput(projection.output());
        return taskMsg;
    }

    public TaskDetailStore.TaskMessageProjection toStorageProjection() {
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                input,
                payloadRef,
                status != null ? TaskMessageProjectionStatus.valueOf(status.name()) : null,
                assignedTime,
                createTime,
                updateTime,
                startTime,
                completeTime,
                retryCount,
                maxRetryCount,
                errorMessage,
                errorCode,
                finalReason != null ? TaskMessageProjectionFinalReason.valueOf(finalReason.name()) : null,
                output,
                latestAttemptId,
                latestAttemptWorkerId,
                latestAttemptWorkerContextId,
                latestAttemptBatchId
        );
    }

    private Map<String, Object> copyNullableMap(Map<String, Object> source) {
        return source == null ? null : new LinkedHashMap<>(source);
    }
}
