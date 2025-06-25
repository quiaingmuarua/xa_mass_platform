package com.xa.mass.eventbus.enums.assignment;

/**
 * 分配类型枚举
 */
public enum AssignmentType {
    DEVICE_ASSIGN("设备分配"),
    MSG_ASSIGN("消息分配");

    private final String description;

    AssignmentType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
} 