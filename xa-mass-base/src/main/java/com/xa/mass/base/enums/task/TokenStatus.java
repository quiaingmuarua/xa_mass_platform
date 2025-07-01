package com.xa.mass.base.enums.task;

/**
 * Token状态枚举
 * 负责承载调度、授权、切号、并发等全部动态信息
 * 状态流转：LOGIN_READY -> BIND_READY -> SENDING -> LOGIN_READY
 * 或者：LOGIN_READY -> BLOCKED -> LOGIN_READY
 * 或者：任意状态 -> INVALID
 */
public enum TokenStatus {
    /**
     * 已登录可注册，空闲
     */
    LOGIN_READY("空闲可用"),
    
    /**
     * 已绑定任务，准备发送
     */
    BIND_READY("已绑定"),
    
    /**
     * 发送中（正在处理任务）
     */
    SENDING("发送中"),
    
    /**
     * 禁用/锁定/待切换/异常
     */
    BLOCKED("已锁定"),
    
    /**
     * 失效/注销/超时
     */
    INVALID("已失效");
    
    private final String description;
    
    TokenStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 检查是否可以分配
     */
    public boolean isAllocatable() {
        return this == LOGIN_READY;
    }
    
    /**
     * 检查是否正在使用中
     */
    public boolean isInUse() {
        return this == BIND_READY || this == SENDING;
    }
    
    /**
     * 检查是否可用
     */
    public boolean isAvailable() {
        return this == LOGIN_READY || this == BIND_READY;
    }
    
    /**
     * 检查是否为最终状态
     */
    public boolean isFinal() {
        return this == INVALID;
    }
    
    /**
     * 检查是否可以转换为目标状态
     */
    public boolean canTransitionTo(TokenStatus targetStatus) {
        if (targetStatus == INVALID) {
            return true; // 任何状态都可以变为失效
        }
        
        switch (this) {
            case LOGIN_READY:
                return targetStatus == BIND_READY || targetStatus == BLOCKED;
            case BIND_READY:
                return targetStatus == SENDING || targetStatus == LOGIN_READY || targetStatus == BLOCKED;
            case SENDING:
                return targetStatus == LOGIN_READY || targetStatus == BLOCKED;
            case BLOCKED:
                return targetStatus == LOGIN_READY;
            case INVALID:
                return false; // 失效状态不可再转换
            default:
                return false;
        }
    }
} 