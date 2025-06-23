package com.xa.mass.engine.model.enums;

/**
 * 任务消息状态枚举
 * 用于记录单条任务的下发与执行过程
 * 状态流转：INIT -> BINDING -> SENT -> RUNNING -> SUCCESS/FAILED/EXPIRED
 */
public enum TaskMsgStatus {
    /**
     * 刚创建，等待下发
     */
    INIT("初始"),
    
    /**
     * batch绑定，准备调度
     */
    BINDING("绑定中"),
    
    /**
     * 已下发，等待响应
     */
    SENT("已发送"),
    
    /**
     * 设备已接收，处理中
     */
    RUNNING("执行中"),
    
    /**
     * 成功完成
     */
    SUCCESS("成功"),
    
    /**
     * 失败/异常
     */
    FAILED("失败"),
    
    /**
     * 过期未完成
     */
    EXPIRED("已过期");
    
    private final String description;
    
    TaskMsgStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 检查是否为最终状态
     */
    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == EXPIRED;
    }
    
    /**
     * 检查是否正在处理中
     */
    public boolean isProcessing() {
        return this == BINDING || this == SENT || this == RUNNING;
    }
    
    /**
     * 检查是否成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
    
    /**
     * 检查是否失败
     */
    public boolean isFailed() {
        return this == FAILED || this == EXPIRED;
    }
    
    /**
     * 检查是否可以重试
     */
    public boolean isRetryable() {
        return this == FAILED || this == EXPIRED;
    }
    
    /**
     * 检查是否可以转换为目标状态
     */
    public boolean canTransitionTo(TaskMsgStatus targetStatus) {
        switch (this) {
            case INIT:
                return targetStatus == BINDING;
            case BINDING:
                return targetStatus == SENT || targetStatus == FAILED;
            case SENT:
                return targetStatus == RUNNING || targetStatus == FAILED || targetStatus == EXPIRED;
            case RUNNING:
                return targetStatus == SUCCESS || targetStatus == FAILED || targetStatus == EXPIRED;
            case SUCCESS:
            case FAILED:
            case EXPIRED:
                return false; // 最终状态不可再转换
            default:
                return false;
        }
    }
} 