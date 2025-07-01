package com.xa.mass.base.enums.device;

/**
 * 设备状态枚举
 * 仅反映基础物理与环境资源状态，不涉及调度与分配
 */
public enum DeviceStatus {
    /**
     * 网络在线
     */
    ONLINE("在线"),
    
    /**
     * 不在线
     */
    OFFLINE("离线"),
    
    /**
     * 长时间无心跳/锁超时
     */
    EXPIRED("已过期");
    
    private final String description;
    
    DeviceStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 检查设备是否可用
     */
    public boolean isAvailable() {
        return this == ONLINE;
    }
    
    /**
     * 检查设备是否不可用
     */
    public boolean isUnavailable() {
        return this == OFFLINE || this == EXPIRED;
    }
} 