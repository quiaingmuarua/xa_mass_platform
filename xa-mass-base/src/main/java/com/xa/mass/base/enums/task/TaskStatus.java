package com.xa.mass.base.enums.task;

/**
 * 任务状态枚举
 * 状态流转：NEW -> BLOCKED -> READY -> RUNNING -> TERMINAL
 * 或者：NEW -> BLOCKED -> READY -> BLOCKED -> READY -> RUNNING -> TERMINAL
 */
public enum TaskStatus {
    /**
     * 新建，未审核
     */
    NEW("新建"),

    /**
     * 审核通过但被阻塞，暂不可调度
     */
    BLOCKED("已阻塞"),

    /**
     * 审核通过，待分配设备
     */
    READY("待分配"),

    /**
     * 已调度，设备已匹配
     */
    RUNNING("运行中"),

    /**
     * 被暂停，暂不可调度
     */
    PAUSED("已暂停"),

    /**
     * 终止，结束/异常/人工关闭
     */
    TERMINAL("已终止");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 检查是否可以转换为目标状态
     */
    public boolean canTransitionTo(TaskStatus targetStatus) {
        switch (this) {
            case NEW:
                return targetStatus == BLOCKED || targetStatus == TERMINAL;
            case BLOCKED:
                return targetStatus == READY || targetStatus == TERMINAL;
            case READY:
                return targetStatus == RUNNING || targetStatus == BLOCKED || targetStatus == TERMINAL;
            case RUNNING:
                return targetStatus == BLOCKED || targetStatus == PAUSED || targetStatus == TERMINAL;
            case PAUSED:
                return targetStatus == READY || targetStatus == TERMINAL;
            case TERMINAL:
                return false; // 终止状态不可再转换
            default:
                return false;
        }
    }

    /**
     * 检查是否为最终状态
     */
    public boolean isFinal() {
        return this == TERMINAL;
    }

    /**
     * 检查是否可以调度
     */
    public boolean isSchedulable() {
        return this == READY;
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return this == RUNNING;
    }

    /**
     * 检查是否被阻塞
     */
    public boolean isBlocked() {
        return this == BLOCKED;
    }

    /**
     * 检查是否被暂停
     */
    public boolean isPaused() {
        return this == PAUSED;
    }
} 