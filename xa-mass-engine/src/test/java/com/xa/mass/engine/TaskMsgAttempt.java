package com.xa.mass.engine;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class TaskMsgAttempt {
    private String attemptId;
    private String taskId;
    private String messageId;
    private int attemptNo;
    private String workerId;
    private String workerContextId;
    private String batchId;
    private TaskMsgAttemptStatus status = TaskMsgAttemptStatus.CREATED;
    private LocalDateTime leaseExpireTime;
    private LocalDateTime dispatchTime;
    private LocalDateTime ackTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private TaskMsgAttemptFinalReason finalReason;
    private String errorMessage;
    private String errorCode;
    private Map<String, Object> output;
    private LocalDateTime createTime = LocalDateTime.now();
    private LocalDateTime updateTime = createTime;

    public TaskMsgAttempt() {
    }

    public TaskMsgAttempt(String attemptId, String taskId, String messageId, int attemptNo) {
        this.attemptId = attemptId;
        this.taskId = taskId;
        this.messageId = messageId;
        this.attemptNo = attemptNo;
    }

    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getWorkerContextId() { return workerContextId; }
    public void setWorkerContextId(String workerContextId) { this.workerContextId = workerContextId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public TaskMsgAttemptStatus getStatus() { return status; }
    public void setStatus(TaskMsgAttemptStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        this.updateTime = LocalDateTime.now();
        if (status == TaskMsgAttemptStatus.DISPATCHED && dispatchTime == null) {
            dispatchTime = updateTime;
        } else if (status == TaskMsgAttemptStatus.ACKED && ackTime == null) {
            ackTime = updateTime;
        } else if (status == TaskMsgAttemptStatus.RUNNING && startTime == null) {
            startTime = updateTime;
        } else if (status.isFinal() && finishTime == null) {
            finishTime = updateTime;
        }
    }
    public LocalDateTime getLeaseExpireTime() { return leaseExpireTime; }
    public void setLeaseExpireTime(LocalDateTime leaseExpireTime) { this.leaseExpireTime = leaseExpireTime; }
    public LocalDateTime getDispatchTime() { return dispatchTime; }
    public void setDispatchTime(LocalDateTime dispatchTime) { this.dispatchTime = dispatchTime; }
    public LocalDateTime getAckTime() { return ackTime; }
    public void setAckTime(LocalDateTime ackTime) { this.ackTime = ackTime; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }
    public TaskMsgAttemptFinalReason getFinalReason() { return finalReason; }
    public void setFinalReason(TaskMsgAttemptFinalReason finalReason) { this.finalReason = finalReason; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Map<String, Object> getOutput() { return output; }
    public void setOutput(Map<String, Object> output) { this.output = output == null ? null : new LinkedHashMap<>(output); }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public boolean transitionTo(TaskMsgAttemptStatus targetStatus) {
        if (!status.canTransitionTo(targetStatus)) {
            return false;
        }
        setStatus(targetStatus);
        return true;
    }

    public boolean markLeased(LocalDateTime leaseExpireTime) {
        if (!transitionTo(TaskMsgAttemptStatus.LEASED)) {
            return false;
        }
        this.leaseExpireTime = leaseExpireTime;
        return true;
    }

    public boolean markDispatched() { return transitionTo(TaskMsgAttemptStatus.DISPATCHED); }
    public boolean markRunning() { return transitionTo(TaskMsgAttemptStatus.RUNNING) || status == TaskMsgAttemptStatus.RUNNING; }

    public boolean markSucceeded() {
        if (!(status == TaskMsgAttemptStatus.RUNNING || status == TaskMsgAttemptStatus.DISPATCHED || status == TaskMsgAttemptStatus.ACKED)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        finalReason = TaskMsgAttemptFinalReason.SUCCESS;
        return true;
    }

    public boolean markFailed(TaskMsgAttemptFinalReason finalReason, String errorMessage) {
        if (!(status == TaskMsgAttemptStatus.RUNNING || status == TaskMsgAttemptStatus.DISPATCHED || status == TaskMsgAttemptStatus.ACKED)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.FAILED);
        this.finalReason = finalReason;
        this.errorMessage = errorMessage;
        return true;
    }

    public boolean markFailed(TaskMsgAttemptFinalReason finalReason, String errorMessage, String errorCode) {
        if (!markFailed(finalReason, errorMessage)) {
            return false;
        }
        this.errorCode = errorCode;
        return true;
    }

    public boolean markExpired(TaskMsgAttemptFinalReason finalReason, String errorMessage) {
        if (!(status == TaskMsgAttemptStatus.LEASED || status == TaskMsgAttemptStatus.DISPATCHED
                || status == TaskMsgAttemptStatus.ACKED || status == TaskMsgAttemptStatus.RUNNING)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.EXPIRED);
        this.finalReason = finalReason;
        this.errorMessage = errorMessage;
        return true;
    }

    public boolean markRevokedForRetry(String errorMessage, String errorCode) {
        if (status.isFinal()) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.REVOKED);
        this.finalReason = TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        return true;
    }

    public static TaskMsgAttempt fromStorageProjection(TaskDetailStore.TaskMessageAttemptProjection projection) {
        if (projection == null) {
            return null;
        }
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo()
        );
        attempt.setWorkerId(projection.workerId());
        attempt.setWorkerContextId(projection.workerContextId());
        attempt.setBatchId(projection.batchId());
        attempt.setStatus(projection.status() != null
                ? TaskMsgAttemptStatus.valueOf(projection.status().name())
                : TaskMsgAttemptStatus.CREATED);
        attempt.setFinalReason(projection.finalReason() != null
                ? TaskMsgAttemptFinalReason.valueOf(projection.finalReason().name())
                : null);
        attempt.setErrorMessage(projection.errorMessage());
        attempt.setErrorCode(projection.errorCode());
        attempt.setOutput(projection.output());
        return attempt;
    }

    public TaskDetailStore.TaskMessageAttemptProjection toStorageProjection() {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                taskId,
                messageId,
                attemptNo,
                workerId,
                workerContextId,
                batchId,
                status != null ? TaskMessageAttemptProjectionStatus.valueOf(status.name()) : null,
                finalReason != null ? TaskMessageAttemptProjectionFinalReason.valueOf(finalReason.name()) : null,
                errorMessage,
                errorCode,
                output
        );
    }
}
