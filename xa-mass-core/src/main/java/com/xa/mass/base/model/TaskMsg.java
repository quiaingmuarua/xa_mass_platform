package com.xa.mass.base.model;

import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 任务消息实体
 * 记录单条任务的下发与执行过程
 * 用于批次调度、失败重试、回执跟踪
 */
public class TaskMsg {
    /**
     * 消息唯一标识
     */
    private String msgId;

    /**
     * 所属任务
     */
    private String taskId;

    /**
     * 目标设备
     */
    private String deviceId;

    /**
     * 使用token
     */
    private String tokenId;

    /**
     * 状态
     */
    private TaskMsgStatus status;

    /**
     * 批次信息
     */
    private String batchId;

    /**
     * 发送时间
     */
    private LocalDateTime sendTime;

    /**
     * 最终回执/响应码
     */
    private String result;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 开始执行时间
     */
    private LocalDateTime startTime;

    /**
     * 完成时间
     */
    private LocalDateTime completeTime;

    /**
     * 重试次数
     */
    private int retryCount;

    /**
     * 最大重试次数
     */
    private int maxRetryCount;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 目标号码
     */
    private String target;

    public TaskMsg() {
        this.status = TaskMsgStatus.INIT;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.retryCount = 0;
        this.maxRetryCount = 3;
    }

    public TaskMsg(String msgId, String taskId, String target) {
        this();
        this.msgId = msgId;
        this.taskId = taskId;
        this.target = target;
    }

    // Getters and Setters
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

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public TaskMsgStatus getStatus() {
        return status;
    }

    public void setStatus(TaskMsgStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();

        // 记录关键时间点
        if (status == TaskMsgStatus.RUNNING && startTime == null) {
            this.startTime = LocalDateTime.now();
        } else if (status.isFinal()) {
            this.completeTime = LocalDateTime.now();
        }
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public LocalDateTime getSendTime() {
        return sendTime;
    }

    public void setSendTime(LocalDateTime sendTime) {
        this.sendTime = sendTime;
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

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * 检查消息是否已完成
     */
    public boolean isCompleted() {
        return status.isFinal();
    }

    /**
     * 检查消息是否成功
     */
    public boolean isSuccess() {
        return status.isSuccess();
    }

    /**
     * 检查消息是否失败
     */
    public boolean isFailed() {
        return status.isFailed();
    }

    /**
     * 检查消息是否正在处理中
     */
    public boolean isProcessing() {
        return status.isProcessing();
    }

    /**
     * 检查是否可以重试
     */
    public boolean canRetry() {
        return status.isRetryable() && retryCount < maxRetryCount;
    }

    /**
     * 增加重试次数
     */
    public void incrementRetryCount() {
        this.retryCount++;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 将消息重置到 INIT 状态以进行下一次重试。
     * 只通过此方法触发 FAILED/EXPIRED → INIT 的反向转换，不走通用状态机。
     *
     * @return true 表示已重置并登记本次重试；false 表示不可重试
     */
    public boolean resetForRetry() {
        if (!canRetry()) {
            return false;
        }
        incrementRetryCount();
        this.status = TaskMsgStatus.INIT;
        this.deviceId = null;
        this.tokenId = null;
        this.startTime = null;
        this.completeTime = null;
        this.errorMessage = null;
        this.updateTime = LocalDateTime.now();
        return true;
    }

    /**
     * 获取执行时长（毫秒）
     */
    public long getExecutionDuration() {
        if (startTime == null) {
            return 0;
        }
        LocalDateTime endTime = completeTime != null ? completeTime : LocalDateTime.now();
        return java.time.Duration.between(startTime, endTime).toMillis();
    }

    /**
     * 状态转换
     */
    public boolean transitionTo(TaskMsgStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            return true;
        }
        return false;
    }

    /**
     * 标记为发送
     */
    public boolean markAsSent() {
        if (transitionTo(TaskMsgStatus.SENT)) {
            setSendTime(LocalDateTime.now());
            return true;
        }
        return false;
    }

    /**
     * 标记为开始执行
     */
    public boolean markAsRunning() {
        return transitionTo(TaskMsgStatus.RUNNING);
    }

    /**
     * 标记为成功
     */
    public boolean markAsSuccess(String result) {
        if (transitionTo(TaskMsgStatus.SUCCESS)) {
            setResult(result);
            return true;
        }
        return false;
    }

    /**
     * 标记为失败
     */
    public boolean markAsFailed(String errorMessage) {
        if (transitionTo(TaskMsgStatus.FAILED)) {
            setErrorMessage(errorMessage);
            return true;
        }
        return false;
    }

    /**
     * 标记为过期
     */
    public boolean markAsExpired() {
        return transitionTo(TaskMsgStatus.EXPIRED);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
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
                ", deviceId='" + deviceId + '\'' +
                ", tokenId='" + tokenId + '\'' +
                ", status=" + status +
                ", batchId='" + batchId + '\'' +
                ", retryCount=" + retryCount +
                ", result='" + result + '\'' +
                '}';
    }
} 