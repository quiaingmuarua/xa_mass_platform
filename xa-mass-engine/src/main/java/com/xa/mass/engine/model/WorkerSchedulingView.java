package com.xa.mass.engine.model;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerReachabilityState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transitional worker-level scheduling read view.
 *
 * <p>During WorkerContext convergence, context data is flattened here as
 * scheduling attributes while legacy context fields remain available to current
 * rules and trace consumers.</p>
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

    private final boolean hasWorkerContext;
    private final String workerContextId;
    private final String workerContextProject;
    private final WorkerContextStatus workerContextStatus;
    private final Set<String> workerContextRoutingTags;
    private final Map<String, String> workerContextAttributes;
    private final boolean workerContextAllocatable;
    private final boolean workerContextAvailable;
    private final boolean workerContextUsable;
    private final boolean workerContextReserved;
    private final boolean workerContextOccupied;

    private final String schedulingResourceId;
    private final String schedulingProject;
    private final Set<String> schedulingRoutingTags;
    private final Map<String, String> schedulingAttributes;

    private WorkerSchedulingView(Worker worker,
                                 WorkerContext workerContext,
                                 WorkerReachabilityState reachability,
                                 boolean dispatchEnabled,
                                 boolean workerLocked) {
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

        this.hasWorkerContext = workerContext != null;
        this.workerContextId = workerContext != null ? workerContext.getWorkerContextId() : null;
        this.workerContextProject = workerContext != null ? workerContext.getProject() : null;
        this.workerContextStatus = workerContext != null ? workerContext.getStatus() : null;
        this.workerContextRoutingTags = workerContext != null && workerContext.getRoutingTags() != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(workerContext.getRoutingTags()))
                : Set.of();
        this.workerContextAttributes = workerContext != null ? copyMap(workerContext.getAttributes()) : Map.of();
        this.workerContextAllocatable = workerContext != null && workerContext.isAllocatable();
        this.workerContextAvailable = workerContext != null && workerContext.isAvailable();
        this.workerContextUsable = workerContext != null && workerContext.isUsable();
        this.workerContextReserved = workerContext != null && workerContext.isReserved();
        this.workerContextOccupied = workerContext != null && workerContext.isOccupied();

        this.schedulingResourceId = workerContextId != null ? workerContextId : workerId;
        this.schedulingProject = workerContextProject;
        this.schedulingRoutingTags = workerContextRoutingTags;
        this.schedulingAttributes = mergeAttributes(workerAttributes, workerContextAttributes);
    }

    public static WorkerSchedulingView from(Worker worker,
                                            WorkerContext workerContext,
                                            WorkerReachabilityState reachability,
                                            boolean dispatchEnabled,
                                            boolean workerLocked) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        return new WorkerSchedulingView(worker, workerContext, reachability, dispatchEnabled, workerLocked);
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

    public boolean hasWorkerContext() {
        return hasWorkerContext;
    }

    public String workerContextId() {
        return workerContextId;
    }

    public String workerContextProject() {
        return workerContextProject;
    }

    public WorkerContextStatus workerContextStatus() {
        return workerContextStatus;
    }

    public String workerContextStatusName() {
        return workerContextStatus != null ? workerContextStatus.name() : null;
    }

    public Set<String> workerContextRoutingTags() {
        return workerContextRoutingTags;
    }

    public Map<String, String> workerContextAttributes() {
        return workerContextAttributes;
    }

    public boolean workerContextAllocatable() {
        return workerContextAllocatable;
    }

    public boolean workerContextAvailable() {
        return workerContextAvailable;
    }

    public boolean workerContextUsable() {
        return workerContextUsable;
    }

    public boolean workerContextReserved() {
        return workerContextReserved;
    }

    public boolean workerContextOccupied() {
        return workerContextOccupied;
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

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, String> mergeAttributes(Map<String, String> workerAttributes,
                                                       Map<String, String> workerContextAttributes) {
        if ((workerAttributes == null || workerAttributes.isEmpty())
                && (workerContextAttributes == null || workerContextAttributes.isEmpty())) {
            return Map.of();
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (workerAttributes != null) {
            merged.putAll(workerAttributes);
        }
        if (workerContextAttributes != null) {
            merged.putAll(workerContextAttributes);
        }
        return Collections.unmodifiableMap(merged);
    }
}
