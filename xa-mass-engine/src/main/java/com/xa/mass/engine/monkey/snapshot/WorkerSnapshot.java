package com.xa.mass.engine.monkey.snapshot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Worker 属性快照
 */
public class WorkerSnapshot {
    private String workerId;
    private String workerStatus;
    private String agentVersion;
    private LocalDateTime lastHeartbeat;
    private List<String> supportedProjects;
    private String workerGroupId;
    private String adapterId;
    private String onlineStrategy;
    private Map<String, String> attributes;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int appCount;
    private boolean isWorkerAvailable;
    private boolean isWorkerLocked;

    public WorkerSnapshot() {}

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getWorkerStatus() { return workerStatus; }
    public void setWorkerStatus(String workerStatus) { this.workerStatus = workerStatus; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public List<String> getSupportedProjects() { return supportedProjects; }
    public void setSupportedProjects(List<String> supportedProjects) { this.supportedProjects = supportedProjects; }
    public String getWorkerGroupId() { return workerGroupId; }
    public void setWorkerGroupId(String workerGroupId) { this.workerGroupId = workerGroupId; }
    public String getAdapterId() { return adapterId; }
    public void setAdapterId(String adapterId) { this.adapterId = adapterId; }
    public String getOnlineStrategy() { return onlineStrategy; }
    public void setOnlineStrategy(String onlineStrategy) { this.onlineStrategy = onlineStrategy; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public int getAppCount() { return appCount; }
    public void setAppCount(int appCount) { this.appCount = appCount; }
    public boolean isWorkerAvailable() { return isWorkerAvailable; }
    public void setWorkerAvailable(boolean workerAvailable) { isWorkerAvailable = workerAvailable; }
    public boolean isWorkerLocked() { return isWorkerLocked; }
    public void setWorkerLocked(boolean workerLocked) { isWorkerLocked = workerLocked; }
}
