package com.xa.mass.engine.v2.entity;

import lombok.Data;
import java.util.Map;

@Data
public class DeviceEntity {
    private String deviceId;
    private String deviceStatus; // ONLINE OFFLINE BUSY MAINTENANCE
    private String agentVersion;
    private String onlineStrategy;
    private String groupId;
    private Map<String,String> projectTokens; //key project, value tokenId
    private long lockExpireTime;
    private long lastHeartbeat;
    private long createTime;
    private long updateTime;

    // 构造函数
    public DeviceEntity() {}

    public DeviceEntity(String deviceId, String deviceStatus) {
        this.deviceId = deviceId;
        this.deviceStatus = deviceStatus;
        this.createTime = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
        this.lastHeartbeat = System.currentTimeMillis();
    }

    // 业务方法
    public boolean isOnline() {
        return "ONLINE".equals(deviceStatus);
    }

    public boolean isOffline() {
        return "OFFLINE".equals(deviceStatus);
    }

    public boolean isBusy() {
        return "BUSY".equals(deviceStatus);
    }

    public boolean isAvailable() {
        return isOnline() && !isBusy();
    }

    public boolean isLocked() {
        return lockExpireTime > System.currentTimeMillis();
    }

    public void markAsOnline() {
        this.deviceStatus = "ONLINE";
        this.lastHeartbeat = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsOffline() {
        this.deviceStatus = "OFFLINE";
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsBusy() {
        this.deviceStatus = "BUSY";
        this.updateTime = System.currentTimeMillis();
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
    }

    public void lock(long lockDurationMs) {
        this.lockExpireTime = System.currentTimeMillis() + lockDurationMs;
        this.updateTime = System.currentTimeMillis();
    }

    public void unlock() {
        this.lockExpireTime = 0;
        this.updateTime = System.currentTimeMillis();
    }

    public String getTokenForProject(String project) {
        return projectTokens != null ? projectTokens.get(project) : null;
    }

    public void setTokenForProject(String project, String tokenId) {
        if (projectTokens == null) {
            projectTokens = new java.util.HashMap<>();
        }
        projectTokens.put(project, tokenId);
        this.updateTime = System.currentTimeMillis();
    }

    public boolean hasTokenForProject(String project) {
        return projectTokens != null && projectTokens.containsKey(project);
    }

    public long getHeartbeatAge() {
        return System.currentTimeMillis() - lastHeartbeat;
    }

    public boolean isHeartbeatExpired(long maxAgeMs) {
        return getHeartbeatAge() > maxAgeMs;
    }
}