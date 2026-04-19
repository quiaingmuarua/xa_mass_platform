package com.xa.mass.base.model;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One concrete execution attempt for a logical task message.
 */
public class TaskMsgAttempt {
    private String attemptId;
    private String taskId;
    private String msgId;
    private int attemptNo;
    private String workerId;
    private String workerContextId;
    private String batchId;
    private TaskMsgAttemptStatus status;
    private LocalDateTime leaseExpireTime;
    private LocalDateTime dispatchTime;
    private LocalDateTime ackTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private TaskMsgAttemptFinalReason finalReason;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public TaskMsgAttempt() {
        this.status = TaskMsgAttemptStatus.CREATED;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public TaskMsgAttempt(String attemptId, String taskId, String msgId, int attemptNo) {
        this();
        this.attemptId = attemptId;
        this.taskId = taskId;
        this.msgId = msgId;
        this.attemptNo = attemptNo;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
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

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public TaskMsgAttemptStatus getStatus() {
        return status;
    }

    public void setStatus(TaskMsgAttemptStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        this.updateTime = LocalDateTime.now();
        if (status == TaskMsgAttemptStatus.DISPATCHED && dispatchTime == null) {
            this.dispatchTime = LocalDateTime.now();
        } else if (status == TaskMsgAttemptStatus.ACKED && ackTime == null) {
            this.ackTime = LocalDateTime.now();
        } else if (status == TaskMsgAttemptStatus.RUNNING && startTime == null) {
            this.startTime = LocalDateTime.now();
        } else if (status.isFinal() && finishTime == null) {
            this.finishTime = LocalDateTime.now();
        }
    }

    public LocalDateTime getLeaseExpireTime() {
        return leaseExpireTime;
    }

    public void setLeaseExpireTime(LocalDateTime leaseExpireTime) {
        this.leaseExpireTime = leaseExpireTime;
    }

    public LocalDateTime getDispatchTime() {
        return dispatchTime;
    }

    public void setDispatchTime(LocalDateTime dispatchTime) {
        this.dispatchTime = dispatchTime;
    }

    public LocalDateTime getAckTime() {
        return ackTime;
    }

    public void setAckTime(LocalDateTime ackTime) {
        this.ackTime = ackTime;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }

    public TaskMsgAttemptFinalReason getFinalReason() {
        return finalReason;
    }

    public void setFinalReason(TaskMsgAttemptFinalReason finalReason) {
        this.finalReason = finalReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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

    public boolean markDispatched() {
        return transitionTo(TaskMsgAttemptStatus.DISPATCHED);
    }

    public boolean markAcked() {
        return transitionTo(TaskMsgAttemptStatus.ACKED);
    }

    public boolean markRunning() {
        if (status == TaskMsgAttemptStatus.DISPATCHED) {
            transitionTo(TaskMsgAttemptStatus.ACKED);
        }
        return transitionTo(TaskMsgAttemptStatus.RUNNING);
    }

    public boolean markSucceeded() {
        if (!status.canTransitionTo(TaskMsgAttemptStatus.SUCCEEDED)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        this.finalReason = TaskMsgAttemptFinalReason.SUCCESS;
        return true;
    }

    public boolean markFailed(TaskMsgAttemptFinalReason finalReason, String errorMessage) {
        if (!status.canTransitionTo(TaskMsgAttemptStatus.FAILED)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.FAILED);
        this.finalReason = finalReason;
        this.errorMessage = errorMessage;
        return true;
    }

    public boolean markExpired(TaskMsgAttemptFinalReason finalReason, String errorMessage) {
        if (!status.canTransitionTo(TaskMsgAttemptStatus.EXPIRED)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.EXPIRED);
        this.finalReason = finalReason;
        this.errorMessage = errorMessage;
        return true;
    }

    public boolean markRevokedForRetry() {
        if (!status.canTransitionTo(TaskMsgAttemptStatus.REVOKED)) {
            return false;
        }
        setStatus(TaskMsgAttemptStatus.REVOKED);
        this.finalReason = TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY;
        return true;
    }

    public boolean isFinal() {
        return status != null && status.isFinal();
    }
}
