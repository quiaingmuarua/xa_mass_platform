package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default worker inspection model.
 */
public final class WorkerSnapshot {

    private final String workerId;
    private final String status;
    private final String agentVersion;
    private final List<String> supportedProjects;
    private final List<String> supportedEventCodes;
    private final List<WorkerEventBinding> eventBindings;
    private final String workerGroupId;
    private final String transportHint;
    private final int maxConcurrentWork;
    private final Map<String, String> attributes;

    public WorkerSnapshot(String workerId,
                          String status,
                          String agentVersion,
                          List<String> supportedProjects,
                          List<String> supportedEventCodes,
                          List<WorkerEventBinding> eventBindings,
                          String workerGroupId,
                          String transportHint,
                          int maxConcurrentWork,
                          Map<String, String> attributes) {
        this.workerId = workerId;
        this.status = status;
        this.agentVersion = agentVersion;
        this.supportedProjects = copyList(supportedProjects);
        this.supportedEventCodes = copyList(supportedEventCodes);
        this.eventBindings = copyBindingList(eventBindings);
        this.workerGroupId = workerGroupId;
        this.transportHint = transportHint;
        this.maxConcurrentWork = Math.max(1, maxConcurrentWork);
        this.attributes = copyMap(attributes);
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

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public List<String> getSupportedEventCodes() {
        return supportedEventCodes;
    }

    public List<WorkerEventBinding> getEventBindings() {
        return eventBindings;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public int getMaxConcurrentWork() {
        return maxConcurrentWork;
    }

    public Map<String, String> getAttributes() {
        return attributes;
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

    private static List<WorkerEventBinding> copyBindingList(List<WorkerEventBinding> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return List.copyOf(source);
    }
}
