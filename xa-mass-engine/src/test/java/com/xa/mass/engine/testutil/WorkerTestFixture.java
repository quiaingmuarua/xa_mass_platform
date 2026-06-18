package com.xa.mass.engine.testutil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class WorkerTestFixture {

    private String workerId;
    private String workerGroupId;
    private String agentVersion;
    private LocalDateTime lastHeartbeat;
    private List<String> supportedProjects = List.of();
    private List<String> supportedEventCodes = List.of();
    private String onlineStrategy;
    private int maxConcurrentWork = 1;
    private Map<String, String> attributes = Map.of();

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public void setWorkerGroupId(String workerGroupId) {
        this.workerGroupId = workerGroupId;
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

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public void setSupportedProjects(List<String> supportedProjects) {
        this.supportedProjects = supportedProjects == null ? List.of() : List.copyOf(supportedProjects);
    }

    public List<String> getSupportedEventCodes() {
        return supportedEventCodes;
    }

    public void setSupportedEventCodes(List<String> supportedEventCodes) {
        this.supportedEventCodes = supportedEventCodes == null ? List.of() : List.copyOf(supportedEventCodes);
    }

    public String getOnlineStrategy() {
        return onlineStrategy;
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
        this.attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }
}
