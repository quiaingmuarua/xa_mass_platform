package com.xa.mass.engine.v2.entity;

import lombok.Data;
import java.util.List;

@Data
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
}