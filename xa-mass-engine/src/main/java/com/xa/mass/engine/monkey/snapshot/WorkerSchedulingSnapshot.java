package com.xa.mass.engine.monkey.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worker scheduling read-model snapshot captured for assignment diagnostics.
 *
 * <p>Legacy WorkerContext identity can still appear as payload evidence while
 * the runtime contract carries workerContextId, but this snapshot is worker
 * scheduling view first and does not mirror WorkerContext lifecycle state.</p>
 */
public class WorkerSchedulingSnapshot {
    private String workerId;
    private String workerStatus;
    private String workerGroupId;
    private String agentVersion;
    private List<String> supportedProjects;
    private List<String> supportedEventCodes;
    private Map<String, String> workerAttributes;
    private String reachability;
    private boolean dispatchEnabled;
    private boolean workerLocked;
    private int activeLeaseCount;
    private int reservedCount;
    private int declaredCapacity;
    private double estimatedLoadRatio;
    private boolean hasLegacyWorkerContext;
    private String legacyWorkerContextId;
    private String schedulingResourceId;
    private String schedulingProject;
    private Set<String> schedulingRoutingTags;
    private Map<String, String> schedulingAttributes;
    private boolean schedulingResourceAllocatable;
    private boolean schedulingResourceAvailable;
    private boolean schedulingResourceUsable;

    public WorkerSchedulingSnapshot() {
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getWorkerStatus() {
        return workerStatus;
    }

    public void setWorkerStatus(String workerStatus) {
        this.workerStatus = workerStatus;
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

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public void setSupportedProjects(List<String> supportedProjects) {
        this.supportedProjects = supportedProjects;
    }

    public List<String> getSupportedEventCodes() {
        return supportedEventCodes;
    }

    public void setSupportedEventCodes(List<String> supportedEventCodes) {
        this.supportedEventCodes = supportedEventCodes;
    }

    public Map<String, String> getWorkerAttributes() {
        return workerAttributes;
    }

    public void setWorkerAttributes(Map<String, String> workerAttributes) {
        this.workerAttributes = workerAttributes;
    }

    public String getReachability() {
        return reachability;
    }

    public void setReachability(String reachability) {
        this.reachability = reachability;
    }

    public boolean isDispatchEnabled() {
        return dispatchEnabled;
    }

    public void setDispatchEnabled(boolean dispatchEnabled) {
        this.dispatchEnabled = dispatchEnabled;
    }

    public boolean isWorkerLocked() {
        return workerLocked;
    }

    public void setWorkerLocked(boolean workerLocked) {
        this.workerLocked = workerLocked;
    }

    public int getActiveLeaseCount() {
        return activeLeaseCount;
    }

    public void setActiveLeaseCount(int activeLeaseCount) {
        this.activeLeaseCount = activeLeaseCount;
    }

    public int getReservedCount() {
        return reservedCount;
    }

    public void setReservedCount(int reservedCount) {
        this.reservedCount = reservedCount;
    }

    public int getDeclaredCapacity() {
        return declaredCapacity;
    }

    public void setDeclaredCapacity(int declaredCapacity) {
        this.declaredCapacity = declaredCapacity;
    }

    public double getEstimatedLoadRatio() {
        return estimatedLoadRatio;
    }

    public void setEstimatedLoadRatio(double estimatedLoadRatio) {
        this.estimatedLoadRatio = estimatedLoadRatio;
    }

    public boolean isHasLegacyWorkerContext() {
        return hasLegacyWorkerContext;
    }

    public void setHasLegacyWorkerContext(boolean hasLegacyWorkerContext) {
        this.hasLegacyWorkerContext = hasLegacyWorkerContext;
    }

    public String getLegacyWorkerContextId() {
        return legacyWorkerContextId;
    }

    public void setLegacyWorkerContextId(String legacyWorkerContextId) {
        this.legacyWorkerContextId = legacyWorkerContextId;
    }

    public String getSchedulingResourceId() {
        return schedulingResourceId;
    }

    public void setSchedulingResourceId(String schedulingResourceId) {
        this.schedulingResourceId = schedulingResourceId;
    }

    public String getSchedulingProject() {
        return schedulingProject;
    }

    public void setSchedulingProject(String schedulingProject) {
        this.schedulingProject = schedulingProject;
    }

    public Set<String> getSchedulingRoutingTags() {
        return schedulingRoutingTags;
    }

    public void setSchedulingRoutingTags(Set<String> schedulingRoutingTags) {
        this.schedulingRoutingTags = schedulingRoutingTags;
    }

    public Map<String, String> getSchedulingAttributes() {
        return schedulingAttributes;
    }

    public void setSchedulingAttributes(Map<String, String> schedulingAttributes) {
        this.schedulingAttributes = schedulingAttributes;
    }

    public boolean isSchedulingResourceAllocatable() {
        return schedulingResourceAllocatable;
    }

    public void setSchedulingResourceAllocatable(boolean schedulingResourceAllocatable) {
        this.schedulingResourceAllocatable = schedulingResourceAllocatable;
    }

    public boolean isSchedulingResourceAvailable() {
        return schedulingResourceAvailable;
    }

    public void setSchedulingResourceAvailable(boolean schedulingResourceAvailable) {
        this.schedulingResourceAvailable = schedulingResourceAvailable;
    }

    public boolean isSchedulingResourceUsable() {
        return schedulingResourceUsable;
    }

    public void setSchedulingResourceUsable(boolean schedulingResourceUsable) {
        this.schedulingResourceUsable = schedulingResourceUsable;
    }
}
