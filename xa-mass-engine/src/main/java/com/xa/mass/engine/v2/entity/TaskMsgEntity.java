package com.xa.mass.engine.v2.entity;

import java.util.List;

public class TaskMsgEntity {
    private String msgId;
    private String taskId;
    private String taskMsgStatus; //INIT BINDING RUNNING COMPLETE 完整的生命周期
    private String completeStatus; //INIT SUCCESS FAILED EXPIRED  最终状态明细
    private List<String> deviceIds; //匹配到的设备
    private List<String> deviceTokens; //匹配的到的token
    private List<String> batchRawSeeds; //任务种子

    private int retryCount;
    private long sendTime;
    private long createTime;
    private long startTime;
    private long completeTime;

    // 构造函数
    public TaskMsgEntity() {}

    public TaskMsgEntity(String msgId, String taskId) {
        this.msgId = msgId;
        this.taskId = taskId;
        this.taskMsgStatus = "INIT";
        this.completeStatus = "INIT";
        this.retryCount = 0;
        this.createTime = System.currentTimeMillis();
    }

    // 业务方法
    public boolean isInit() {
        return "INIT".equals(taskMsgStatus);
    }

    public boolean isBinding() {
        return "BINDING".equals(taskMsgStatus);
    }

    public boolean isRunning() {
        return "RUNNING".equals(taskMsgStatus);
    }

    public boolean isComplete() {
        return "COMPLETE".equals(taskMsgStatus);
    }

    public boolean isSuccess() {
        return "SUCCESS".equals(completeStatus);
    }

    public boolean isFailed() {
        return "FAILED".equals(completeStatus);
    }

    public boolean isExpired() {
        return "EXPIRED".equals(completeStatus);
    }

    public void markAsBinding() {
        this.taskMsgStatus = "BINDING";
    }

    public void markAsRunning() {
        this.taskMsgStatus = "RUNNING";
        this.startTime = System.currentTimeMillis();
    }

    public void markAsComplete() {
        this.taskMsgStatus = "COMPLETE";
        this.completeTime = System.currentTimeMillis();
    }

    public void markAsSuccess() {
        this.completeStatus = "SUCCESS";
        this.completeTime = System.currentTimeMillis();
    }

    public void markAsFailed() {
        this.completeStatus = "FAILED";
        this.completeTime = System.currentTimeMillis();
    }

    public void markAsExpired() {
        this.completeStatus = "EXPIRED";
        this.completeTime = System.currentTimeMillis();
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public boolean canRetry(int maxRetries) {
        return retryCount < maxRetries;
    }

    // Getter and Setter methods
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

    public String getTaskMsgStatus() {
        return taskMsgStatus;
    }

    public void setTaskMsgStatus(String taskMsgStatus) {
        this.taskMsgStatus = taskMsgStatus;
    }

    public String getCompleteStatus() {
        return completeStatus;
    }

    public void setCompleteStatus(String completeStatus) {
        this.completeStatus = completeStatus;
    }

    public List<String> getDeviceIds() {
        return deviceIds;
    }

    public void setDeviceIds(List<String> deviceIds) {
        this.deviceIds = deviceIds;
    }

    public List<String> getDeviceTokens() {
        return deviceTokens;
    }

    public void setDeviceTokens(List<String> deviceTokens) {
        this.deviceTokens = deviceTokens;
    }

    public List<String> getBatchRawSeeds() {
        return batchRawSeeds;
    }

    public void setBatchRawSeeds(List<String> batchRawSeeds) {
        this.batchRawSeeds = batchRawSeeds;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getSendTime() {
        return sendTime;
    }

    public void setSendTime(long sendTime) {
        this.sendTime = sendTime;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }
}
