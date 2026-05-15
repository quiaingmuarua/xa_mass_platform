package com.xa.mass.base.model;


import com.xa.mass.base.enums.worker.WorkerStatus;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Worker 实体
 * 仅负责维护自身物理/网络/版本等属性
 * 是否可调度由 Task/WorkerContext 筛选决定
 */
public class Worker {
    private String workerId;
    private WorkerStatus status;
    private String agentVersion;
    private LocalDateTime lastHeartbeat;
    private List<String> supportedProjects;
    private List<String> supportedEventCodes;
    private String workerGroupId;
    private String adapterId;
    private String onlineStrategy;
    private int maxConcurrentWork = 1;
    private Map<String, String> attributes = Collections.emptyMap();
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Worker() {
        this.status = WorkerStatus.OFFLINE;
        this.supportedProjects = Collections.emptyList();
        this.supportedEventCodes = Collections.emptyList();
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public Worker(String workerId, String agentVersion, List<String> supportedProjects) {
        this();
        this.workerId = workerId;
        this.agentVersion = agentVersion;
        this.supportedProjects = supportedProjects;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerStatus status) {
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

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    /**
     * Coarse worker grouping/filter hint only.
     *
     * <p>Capability truth for task-backed and direct-runtime events now lives
     * on {@link #getSupportedEventCodes()}.
     */
    public void setSupportedProjects(List<String> supportedProjects) {
        if (supportedProjects == null || supportedProjects.isEmpty()) {
            this.supportedProjects = Collections.emptyList();
            return;
        }
        this.supportedProjects = List.copyOf(supportedProjects);
    }

    public List<String> getSupportedEventCodes() {
        return supportedEventCodes;
    }

    /**
     * Canonical runtime capability declarations keyed by global event code.
     */
    public void setSupportedEventCodes(List<String> supportedEventCodes) {
        if (supportedEventCodes == null || supportedEventCodes.isEmpty()) {
            this.supportedEventCodes = Collections.emptyList();
            return;
        }
        this.supportedEventCodes = List.copyOf(supportedEventCodes);
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public void setWorkerGroupId(String workerGroupId) {
        this.workerGroupId = workerGroupId;
    }

    public String getOnlineStrategy() {
        return onlineStrategy;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public void setAdapterId(String adapterId) {
        this.adapterId = adapterId;
    }

    public void setOnlineStrategy(String onlineStrategy) {
        this.onlineStrategy = onlineStrategy;
    }

    public int getMaxConcurrentWork() {
        return Math.max(1, maxConcurrentWork);
    }

    public void setMaxConcurrentWork(int maxConcurrentWork) {
        this.maxConcurrentWork = Math.max(1, maxConcurrentWork);
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

    public boolean isAvailable() {
        return status.isAvailable();
    }

    public boolean supportsProject(String projectCode) {
        return supportedProjects != null && supportedProjects.contains(projectCode);
    }

    public boolean supportsEvent(String eventCode) {
        return supportedEventCodes != null && supportedEventCodes.contains(eventCode);
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();

        if (this.status != WorkerStatus.ONLINE) {
            this.status = WorkerStatus.ONLINE;
        }
    }

    public boolean isHeartbeatExpired(int timeoutSeconds) {
        if (lastHeartbeat == null) {
            return true;
        }
        return lastHeartbeat.plusSeconds(timeoutSeconds).isBefore(LocalDateTime.now());
    }

    public boolean transitionTo(WorkerStatus targetStatus) {
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
        Worker worker = (Worker) o;
        return Objects.equals(workerId, worker.workerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerId);
    }

    @Override
    public String toString() {
        return "Worker{" +
                "workerId='" + workerId + '\'' +
                ", status=" + status +
                ", agentVersion='" + agentVersion + '\'' +
                ", lastHeartbeat=" + lastHeartbeat +
                ", supportedProjects=" + supportedProjects +
                ", supportedEventCodes=" + supportedEventCodes +
                ", workerGroupId='" + workerGroupId + '\'' +
                ", adapterId='" + adapterId + '\'' +
                ", onlineStrategy='" + onlineStrategy + '\'' +
                ", maxConcurrentWork=" + getMaxConcurrentWork() +
                ", attributes=" + attributes +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                '}';
    }
}
