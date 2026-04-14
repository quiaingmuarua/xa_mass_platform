package com.xa.mass.base.model;


import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 璁惧瀹炰綋
 * 浠呰礋璐ｇ淮鎶よ嚜韬墿鐞?缃戠粶/鐗堟湰绛夊睘鎬?
 * 鏄惁鍙皟搴︾敱 Task/Token 绛涢€夊喅瀹?
 */
public class Device {
    /**
     * 鍞竴鏍囪瘑
     */
    private String deviceId;

    /**
     * 鐘舵€?
     */
    private DeviceStatus status;

    /**
     * 鎻掍欢/Agent鐗堟湰
     */
    private String agentVersion;

    /**
     * 鏈€鍚庡績璺虫椂闂?
     */
    private LocalDateTime lastHeartbeat;

    /**
     * 鏀寔鐨刾roject鍒楄〃
     */
    private List<Project> supportedProjects;

    /**
     * 鍒嗙粍淇℃伅
     */
    private String deviceGroupId;

    /**
     * 褰撳墠鍒嗛厤閿佽繃鏈熸椂闂?
     */
    /**
     * 绛栫暐瀛楁锛堝彲閫夛級
     */
    private String onlineStrategy;

    private Map<String, String> attributes = Collections.emptyMap();

    /**
     * 鍒涘缓鏃堕棿
     */
    private LocalDateTime createTime;

    /**
     * 鏇存柊鏃堕棿
     */
    private LocalDateTime updateTime;

    public Device() {
        this.status = DeviceStatus.OFFLINE;
        this.supportedProjects = Collections.emptyList();
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public Device(String deviceId, String agentVersion, List<Project> supportedProjects) {
        this();
        this.deviceId = deviceId;
        this.agentVersion = agentVersion;
        this.supportedProjects = supportedProjects;
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
        this.status = Objects.requireNonNull(status, "status");
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

    public List<Project> getSupportedProjects() {
        return supportedProjects;
    }

    public void setSupportedProjects(List<Project> supportedProjects) {
        if (supportedProjects == null || supportedProjects.isEmpty()) {
            this.supportedProjects = Collections.emptyList();
            return;
        }
        this.supportedProjects = List.copyOf(supportedProjects);
    }

    public String getDeviceGroupId() {
        return deviceGroupId;
    }

    public void setDeviceGroupId(String deviceGroupId) {
        this.deviceGroupId = deviceGroupId;
    }

    public String getOnlineStrategy() {
        return onlineStrategy;
    }

    public void setOnlineStrategy(String onlineStrategy) {
        this.onlineStrategy = onlineStrategy;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
            return;
        }
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
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
     * 妫€鏌ヨ澶囨槸鍚﹀彲鐢?
     */
    public boolean isAvailable() {
        return status.isAvailable();
    }

    /**
     * 妫€鏌ヨ澶囨槸鍚︽敮鎸佹寚瀹氬簲鐢?
     */
    public boolean supportsProject(Project project) {
        return supportedProjects != null && supportedProjects.contains(project);
    }

    public boolean supportsProject(String projectCode) {
        if (supportedProjects == null) return false;
        return supportedProjects.stream().anyMatch(p -> p.getCode().equals(projectCode));
    }

    /**
     * 妫€鏌ヨ澶囨槸鍚﹁閿佸畾
     */
    /**
     * 鏇存柊蹇冭烦鏃堕棿
     */
    public void updateHeartbeat() {
        this.lastHeartbeat = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();

        // A fresh heartbeat revives any unavailable device back to ONLINE.
        if (this.status != DeviceStatus.ONLINE) {
            this.status = DeviceStatus.ONLINE;
        }
    }

    /**
     * 妫€鏌ュ績璺虫槸鍚﹁秴鏃?
     */
    public boolean isHeartbeatExpired(int timeoutSeconds) {
        if (lastHeartbeat == null) {
            return true;
        }
        return lastHeartbeat.plusSeconds(timeoutSeconds).isBefore(LocalDateTime.now());
    }

    /**
     * 鐘舵€佽浆鎹?
     */
    public boolean transitionTo(DeviceStatus targetStatus) {
        if (targetStatus != null && this.status.canTransitionTo(targetStatus)) {
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
                ", supportedProjects=" + supportedProjects +
                ", deviceGroupId='" + deviceGroupId + '\'' +
                ", onlineStrategy='" + onlineStrategy + '\'' +
                ", attributes=" + attributes +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                '}';
    }
}
