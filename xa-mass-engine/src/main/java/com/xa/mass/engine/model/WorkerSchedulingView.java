package com.xa.mass.engine.model;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.load.WorkerLoadSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transitional worker-level scheduling read view.
 *
 * <p>During WorkerContext convergence, legacy context identity can still be
 * exposed for runtime/trace compatibility, but scheduling evidence is
 * worker-level.</p>
 */
public final class WorkerSchedulingView {
    private final String workerId;
    private final WorkerStatus workerStatus;
    private final String workerGroupId;
    private final String agentVersion;
    private final List<String> supportedProjects;
    private final List<String> supportedEventCodes;
    private final Map<String, String> workerAttributes;
    private final WorkerReachabilityState reachability;
    private final boolean dispatchEnabled;
    private final boolean workerLocked;
    private final WorkerLoadSnapshot workerLoad;

    private final boolean hasWorkerContext;
    private final String workerContextId;

    private final String schedulingResourceId;
    private final String schedulingProject;
    private final Set<String> schedulingRoutingTags;
    private final Map<String, String> schedulingAttributes;

    private WorkerSchedulingView(Worker worker,
                                 WorkerContext workerContext,
                                 WorkerReachabilityState reachability,
                                 boolean dispatchEnabled,
                                 boolean workerLocked,
                                 WorkerLoadSnapshot workerLoad) {
        this.workerId = worker.getWorkerId();
        this.workerStatus = worker.getStatus();
        this.workerGroupId = worker.getWorkerGroupId();
        this.agentVersion = worker.getAgentVersion();
        this.supportedProjects = worker.getSupportedProjects() == null
                ? List.of()
                : List.copyOf(worker.getSupportedProjects());
        this.supportedEventCodes = worker.getSupportedEventCodes() == null
                ? List.of()
                : List.copyOf(worker.getSupportedEventCodes());
        this.workerAttributes = copyMap(worker.getAttributes());
        this.reachability = reachability == null ? WorkerReachabilityState.UNKNOWN : reachability;
        this.dispatchEnabled = dispatchEnabled;
        this.workerLocked = workerLocked;
        this.workerLoad = workerLoad != null ? workerLoad : WorkerLoadSnapshot.empty(worker.getWorkerId());

        this.hasWorkerContext = workerContext != null;
        this.workerContextId = workerContext != null ? workerContext.getWorkerContextId() : null;

        this.schedulingResourceId = workerId;
        this.schedulingProject = null;
        this.schedulingRoutingTags = workerRoutingTags(workerAttributes);
        this.schedulingAttributes = workerAttributes;
    }

    public static WorkerSchedulingView from(Worker worker,
                                            WorkerContext workerContext,
                                            WorkerReachabilityState reachability,
                                            boolean dispatchEnabled,
                                            boolean workerLocked) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        return new WorkerSchedulingView(worker, workerContext, reachability, dispatchEnabled, workerLocked,
                WorkerLoadSnapshot.empty(worker.getWorkerId()));
    }

    public static WorkerSchedulingView from(Worker worker,
                                            WorkerContext workerContext,
                                            WorkerReachabilityState reachability,
                                            boolean dispatchEnabled,
                                            boolean workerLocked,
                                            WorkerLoadSnapshot workerLoad) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        return new WorkerSchedulingView(worker, workerContext, reachability, dispatchEnabled, workerLocked, workerLoad);
    }

    public String workerId() {
        return workerId;
    }

    public WorkerStatus workerStatus() {
        return workerStatus;
    }

    public String workerStatusName() {
        return workerStatus != null ? workerStatus.name() : null;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public String agentVersion() {
        return agentVersion;
    }

    public List<String> supportedProjects() {
        return supportedProjects;
    }

    public List<String> supportedEventCodes() {
        return supportedEventCodes;
    }

    public boolean supportsProject(String project) {
        return project != null && supportedProjects.contains(project);
    }

    public boolean supportsEvent(String eventCode) {
        return eventCode != null && supportedEventCodes.contains(eventCode);
    }

    public Map<String, String> workerAttributes() {
        return workerAttributes;
    }

    public WorkerReachabilityState reachability() {
        return reachability;
    }

    public boolean isTransportReachable() {
        return reachability == WorkerReachabilityState.ONLINE;
    }

    public boolean dispatchEnabled() {
        return dispatchEnabled;
    }

    public boolean workerLocked() {
        return workerLocked;
    }

    public int activeLeaseCount() {
        return workerLoad.activeLeaseCount();
    }

    public int reservedCount() {
        return workerLoad.reservedCount();
    }

    public int declaredCapacity() {
        return workerLoad.declaredCapacity();
    }

    public double estimatedLoadRatio() {
        return workerLoad.estimatedLoadRatio();
    }

    public boolean hasWorkerContext() {
        return hasWorkerContext;
    }

    public String workerContextId() {
        return workerContextId;
    }

    public boolean schedulingResourceAllocatable() {
        return dispatchEnabled;
    }

    public boolean schedulingResourceAvailable() {
        return dispatchEnabled && isTransportReachable();
    }

    public boolean schedulingResourceUsable() {
        return dispatchEnabled;
    }

    public boolean schedulingResourceReserved() {
        return reservedCount() > 0;
    }

    public boolean schedulingResourceOccupied() {
        return activeLeaseCount() > 0;
    }

    public String schedulingResourceId() {
        return schedulingResourceId;
    }

    public String schedulingProject() {
        return schedulingProject;
    }

    public Set<String> schedulingRoutingTags() {
        return schedulingRoutingTags;
    }

    public Map<String, String> schedulingAttributes() {
        return schedulingAttributes;
    }

    public boolean schedulingProjectMatches(String project) {
        return schedulingProject != null && schedulingProject.equals(project);
    }

    public boolean schedulingRoutingTagsContain(String routingCode) {
        return routingCode != null && schedulingRoutingTags.contains(routingCode);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Set<String> workerRoutingTags(Map<String, String> workerAttributes) {
        if (workerAttributes == null || workerAttributes.isEmpty()) {
            return Set.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        addRoutingTags(tags, workerAttributes.get("routingTag"));
        addRoutingTags(tags, workerAttributes.get("routingTags"));
        if (tags.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(tags);
    }

    private static void addRoutingTags(Set<String> tags, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String tag : value.split(",")) {
            String normalized = tag.trim();
            if (!normalized.isEmpty()) {
                tags.add(normalized);
            }
        }
    }
}
