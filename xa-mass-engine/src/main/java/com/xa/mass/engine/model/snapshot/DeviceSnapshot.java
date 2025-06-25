package com.xa.mass.engine.model.snapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备属性快照
 */
public class DeviceSnapshot {
    private String deviceId;
    private String deviceStatus;
    private String agentVersion;
    private LocalDateTime lastHeartbeat;
    private List<String> supportedApps;
    private String groupId;
    private LocalDateTime lockExpireTime;
    private String onlineStrategy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int appCount;
    private boolean isDeviceAvailable;
    private boolean isDeviceLocked;

    public DeviceSnapshot() {
    }

    // Getters and Setters
    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceStatus() {
        return deviceStatus;
    }

    public void setDeviceStatus(String deviceStatus) {
        this.deviceStatus = deviceStatus;
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

    public int getAppCount() {
        return appCount;
    }

    public void setAppCount(int appCount) {
        this.appCount = appCount;
    }

    public boolean isDeviceAvailable() {
        return isDeviceAvailable;
    }

    public void setDeviceAvailable(boolean deviceAvailable) {
        isDeviceAvailable = deviceAvailable;
    }

    public boolean isDeviceLocked() {
        return isDeviceLocked;
    }

    public void setDeviceLocked(boolean deviceLocked) {
        isDeviceLocked = deviceLocked;
    }
} 