package com.xa.mass.engine.model;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.evidence.WorkerGroupCapabilityView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worker-level scheduling read view.
 *
 * <p>Worker identity and runtime state are worker-level. Capability evidence
 * is materialized from WorkerGroup truth when available; legacy worker-level
 * capability fields are not part of the scheduling read path.</p>
 */
public final class WorkerSchedulingView {
    private final String workerId;
    private final String workerStatusName;
    private final String workerGroupId;
    private final String adapterNodeId;
    private final String agentVersion;
    private final List<String> supportedProjects;
    private final List<String> supportedEventCodes;
    private final Map<String, String> workerAttributes;
    private final WorkerReachabilityState reachability;
    private final boolean dispatchEnabled;
    private final boolean workerLocked;
    private final WorkerLoadSnapshot workerLoad;

    private final String schedulingResourceId;
    private final String schedulingProject;
    private final Set<String> schedulingRoutingTags;
    private final Map<String, String> schedulingAttributes;

    private WorkerSchedulingView(WorkerCandidateRow candidateRow,
                                 WorkerGroupCapabilityView workerGroup,
                                 WorkerReachabilityState reachability,
                                 boolean dispatchEnabled,
                                 boolean workerLocked,
                                 WorkerLoadSnapshot workerLoad) {
        this.workerId = candidateRow.workerId();
        this.workerStatusName = candidateRow.statusName();
        this.workerGroupId = candidateRow.workerGroupId();
        this.adapterNodeId = candidateRow.adapterNodeId();
        this.agentVersion = candidateRow.agentVersion();
        this.supportedProjects = workerGroup == null ? List.of() : List.copyOf(workerGroup.projectCodes());
        this.supportedEventCodes = workerGroup == null ? List.of() : List.copyOf(workerGroup.eventCodes());
        this.workerAttributes = copyMap(candidateRow.attributes());
        this.reachability = reachability == null ? WorkerReachabilityState.UNKNOWN : reachability;
        this.dispatchEnabled = dispatchEnabled;
        this.workerLocked = workerLocked;
        this.workerLoad = workerLoad != null ? workerLoad : WorkerLoadSnapshot.empty(candidateRow.workerId());

        this.schedulingResourceId = workerId;
        this.schedulingProject = null;
        this.schedulingRoutingTags = workerRoutingTags(workerAttributes);
        this.schedulingAttributes = workerAttributes;
    }

    public static WorkerSchedulingView from(WorkerCandidateRow candidateRow,
                                            WorkerGroupCapabilityView workerGroup,
                                            WorkerReachabilityState reachability,
                                            boolean dispatchEnabled,
                                            boolean workerLocked,
                                            WorkerLoadSnapshot workerLoad) {
        if (candidateRow == null) {
            throw new IllegalArgumentException("candidateRow must not be null");
        }
        return new WorkerSchedulingView(candidateRow, workerGroup, reachability, dispatchEnabled, workerLocked,
                workerLoad);
    }

    public static WorkerSchedulingView from(WorkerCandidateRow candidateRow,
                                            WorkerReachabilityState reachability,
                                            boolean dispatchEnabled,
                                            boolean workerLocked,
                                            WorkerLoadSnapshot workerLoad) {
        if (candidateRow == null) {
            throw new IllegalArgumentException("candidateRow must not be null");
        }
        return new WorkerSchedulingView(candidateRow, null, reachability, dispatchEnabled, workerLocked,
                workerLoad);
    }

    public static WorkerSchedulingView from(WorkerCandidateRow candidateRow,
                                            WorkerReachabilityState reachability,
                                            boolean dispatchEnabled,
                                            boolean workerLocked) {
        if (candidateRow == null) {
            throw new IllegalArgumentException("candidateRow must not be null");
        }
        return new WorkerSchedulingView(candidateRow, null, reachability, dispatchEnabled, workerLocked,
                WorkerLoadSnapshot.empty(candidateRow.workerId()));
    }

    public String workerId() {
        return workerId;
    }

    public String workerStatusName() {
        return workerStatusName;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public String adapterNodeId() {
        return adapterNodeId;
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
