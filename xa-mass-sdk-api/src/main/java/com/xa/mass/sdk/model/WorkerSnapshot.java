package com.xa.mass.sdk.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerSnapshot {

    private final String workerId;
    private final String status;
    private final String agentVersion;
    private final LocalDateTime lastHeartbeat;
    private final List<String> supportedProjects;
    private final List<String> supportedEventCodes;
    private final String workerGroupId;
    private final String adapterId;
    private final String onlineStrategy;
    private final int maxConcurrentWork;
    private final Map<String, String> attributes;
    private final LocalDateTime createTime;
    private final LocalDateTime updateTime;

    public WorkerSnapshot(String workerId,
                          String status,
                          String agentVersion,
                          LocalDateTime lastHeartbeat,
                          List<String> supportedProjects,
                          List<String> supportedEventCodes,
                          String workerGroupId,
                          String adapterId,
                          String onlineStrategy,
                          int maxConcurrentWork,
                          Map<String, String> attributes,
                          LocalDateTime createTime,
                          LocalDateTime updateTime) {
        this.workerId = workerId;
        this.status = status;
        this.agentVersion = agentVersion;
        this.lastHeartbeat = lastHeartbeat;
        this.supportedProjects = copyList(supportedProjects);
        this.supportedEventCodes = copyList(supportedEventCodes);
        this.workerGroupId = workerGroupId;
        this.adapterId = adapterId;
        this.onlineStrategy = onlineStrategy;
        this.maxConcurrentWork = Math.max(1, maxConcurrentWork);
        this.attributes = copyMap(attributes);
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getStatus() {
        return status;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public List<String> getSupportedEventCodes() {
        return supportedEventCodes;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getOnlineStrategy() {
        return onlineStrategy;
    }

    public int getMaxConcurrentWork() {
        return maxConcurrentWork;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    private static List<String> copyList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return List.copyOf(source);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
