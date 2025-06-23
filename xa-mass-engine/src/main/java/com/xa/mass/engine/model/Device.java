package com.xa.mass.core.engine.model;

import com.xa.mass.engine.model.enums.DeviceStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 设备实体
 * 仅负责维护自身物理/网络/版本等属性
 * 是否可调度由 Task/Token 筛选决定
 */
public class Device {
    /**
     * 唯一标识
     */
    private String deviceId;
    
    /**
     * 状态
     */
    private DeviceStatus status;
    
    /**
     * 插件/Agent版本
     */
    private String agentVersion;
    
    /**
     * 最后心跳时间
     */
    private LocalDateTime lastHeartbeat;
    
    /**
     * 支持的project/app列表
     */
    private List<String> supportedApps;
    
    /**
     * 分组信息
     */
    private String groupId;
    
    /**
     * 当前分配锁过期时间
     */
    private LocalDateTime lockExpireTime;
    
    /**
     * 策略字段（可选）
     */
    private String onlineStrategy;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    public Device() {
        this.status = DeviceStatus.OFFLINE;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
    
    public Device(String deviceId, String agentVersion, List<String> supportedApps) {
        this();
        this.deviceId = deviceId;
        this.agentVersion = agentVersion;
        this.supportedApps = supportedApps;
    }
    
    // Getters and Setters
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    public DeviceStatus getStatus() {
        return status;
    }
    
    public void setStatus(DeviceStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }
    
    public String getAgentVersion() {
        return agentVersion;
    }
    
    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }
    
    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
        this.updateTime = LocalDateTime.now();
    }
    
    public List<String> getSupportedApps() {
        return supportedApps;
    }
    
    public void setSupportedApps(List<String> supportedApps) {
        this.supportedApps = supportedApps;
    }
    
    public String getGroupId() {
        return groupId;
    }
    
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
    
    public LocalDateTime getLockExpireTime() {
        return lockExpireTime;
    }
    
    public void setLockExpireTime(LocalDateTime lockExpireTime) {
        this.lockExpireTime = lockExpireTime;
    }
    
    public String getOnlineStrategy() {
        return onlineStrategy;
    }
    
    public void setOnlineStrategy(String onlineStrategy) {
        this.onlineStrategy = onlineStrategy;
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
    
    /**
     * 检查设备是否可用
     */
    public boolean isAvailable() {
        return status.isAvailable();
    }
    
    /**
     * 检查设备是否支持指定应用
     */
    public boolean supportsApp(String app) {
        return supportedApps != null && supportedApps.contains(app);
    }
    
    /**
     * 检查设备是否被锁定
     */
    public boolean isLocked() {
        return lockExpireTime != null && lockExpireTime.isAfter(LocalDateTime.now());
    }
    
    /**
     * 更新心跳时间
     */
    public void updateHeartbeat() {
        this.lastHeartbeat = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        
        // 如果设备离线，更新为在线状态
        if (this.status == DeviceStatus.OFFLINE) {
            this.status = DeviceStatus.ONLINE;
        }
    }
    
    /**
     * 检查心跳是否超时
     */
    public boolean isHeartbeatExpired(int timeoutSeconds) {
        if (lastHeartbeat == null) {
            return true;
        }
        return lastHeartbeat.plusSeconds(timeoutSeconds).isBefore(LocalDateTime.now());
    }
    
    /**
     * 状态转换
     */
    public boolean transitionTo(DeviceStatus targetStatus) {
        if (this.status != targetStatus) {
            setStatus(targetStatus);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Device device = (Device) o;
        return Objects.equals(deviceId, device.deviceId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(deviceId);
    }
    
    @Override
    public String toString() {
        return "Device{" +
                "deviceId='" + deviceId + '\'' +
                ", status=" + status +
                ", agentVersion='" + agentVersion + '\'' +
                ", lastHeartbeat=" + lastHeartbeat +
                ", supportedApps=" + supportedApps +
                ", groupId='" + groupId + '\'' +
                ", isLocked=" + isLocked() +
                '}';
    }
} 