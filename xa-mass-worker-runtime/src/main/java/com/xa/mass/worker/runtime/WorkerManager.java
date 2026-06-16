package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
import com.xa.mass.worker.runtime.admission.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateBatch;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerGroupCapabilityView;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityView;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import com.xa.mass.worker.runtime.report.WorkerReportRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;
import com.xa.mass.worker.runtime.resource.WorkerNodeBindingRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceDeclarationRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.worker.runtime.routing.WorkerCandidateBucketPolicies;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import com.xa.mass.worker.runtime.admission.WorkerWarmHintRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.worker.runtime.WorkerAdmissionOwner;
import com.xa.mass.worker.runtime.WorkerCandidateIndex;
import com.xa.mass.worker.runtime.WorkerCandidateSourceOwner;
import com.xa.mass.worker.runtime.WorkerCapabilityAuthority;
import com.xa.mass.worker.runtime.WorkerCapabilityReportApplication;
import com.xa.mass.worker.runtime.WorkerGroupOwner;
import com.xa.mass.worker.runtime.WorkerRelationshipOwner;
import com.xa.mass.worker.runtime.WorkerReportOwner;
import com.xa.mass.worker.runtime.WorkerResourceOwner;
import com.xa.mass.worker.runtime.WorkerRegistrySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Worker access facade for the active engine runtime.
 *
 * <p>Reachability is read through {@link WorkerReachabilityView}, while
 * endpoint leases stay in transport delivery and must not be
 * promoted into worker lifecycle truth.
 */
public class WorkerManager implements WorkerResourceQueryRuntime,
        WorkerResourceDeclarationRuntime,
        WorkerNodeBindingRuntime,
        WorkerHeartbeatRuntime,
        WorkerCandidateRuntime,
        WorkerSchedulingViewRuntime,
        WorkerAdmissionRuntime,
        WorkerAvailabilityWakeupRuntime,
        WorkerDispatchGateRuntime,
        WorkerReportRuntime,
        WorkerWarmHintRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);
    private final WorkerReachabilityView reachabilityView;
    private final WorkerRegistry workerRegistry;
    private final WorkerCandidateBucketPolicy candidateBucketPolicy;
    private final WorkerGroupOwner groupOwner;
    private final WorkerResourceOwner resourceOwner;
    private final WorkerReportOwner reportOwner;
    private final WorkerCandidateSourceOwner candidateSourceOwner;
    private final WorkerAdmissionOwner admissionOwner;
    private final WorkerRelationshipOwner relationshipOwner;
    private volatile WorkerRegistrySnapshot workerRegistrySnapshot;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };

    public WorkerManager(WorkerDeclarationStore workerStorage,
                         WorkerRegistry workerRegistry) {
        this(workerStorage, WorkerReachabilityView.permissive(), workerRegistry);
    }

    public WorkerManager(WorkerDeclarationStore workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerRegistry workerRegistry) {
        this(workerStorage, reachabilityView, new WorkerCapabilityAuthority(), workerRegistry);
    }

    public WorkerManager(WorkerDeclarationStore workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerRegistry workerRegistry,
                         WorkerCandidateBucketPolicy candidateBucketPolicy) {
        this(workerStorage, reachabilityView, new WorkerCapabilityAuthority(), workerRegistry, candidateBucketPolicy);
    }

    WorkerManager(WorkerDeclarationStore workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerCapabilityAuthority capabilityAuthority,
                  WorkerRegistry workerRegistry) {
        this(workerStorage, reachabilityView, capabilityAuthority, workerRegistry, WorkerCandidateBucketPolicies.defaultPolicy());
    }

    WorkerManager(WorkerDeclarationStore workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerCapabilityAuthority capabilityAuthority,
                  WorkerRegistry workerRegistry,
                  WorkerCandidateBucketPolicy candidateBucketPolicy) {
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.candidateBucketPolicy = candidateBucketPolicy != null ? candidateBucketPolicy : WorkerCandidateBucketPolicies.defaultPolicy();
        this.groupOwner = new WorkerGroupOwner(this.workerRegistry);
        this.candidateSourceOwner = new WorkerCandidateSourceOwner(this::getWorkerCandidateIndex);
        this.admissionOwner = new WorkerAdmissionOwner(this.workerRegistry);
        this.relationshipOwner = new WorkerRelationshipOwner(this.groupOwner::hasWorkerGroup);
        this.resourceOwner = new WorkerResourceOwner(
                workerStorage,
                this.workerRegistry,
                this.groupOwner
        );
        this.resourceOwner.syncWorkerRegistrySlots(this.resourceOwner.getAllWorkers());
        this.reportOwner = new WorkerReportOwner(capabilityAuthority, this.resourceOwner, this.groupOwner);
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    @Override
    public void addWorker(WorkerDeclarationRecord worker) {
        resourceOwner.addWorker(toWorker(worker));
        publishWorkerRegistrySnapshot();
        notifyDispatchWakeup("worker registered");
    }

    @Override
    public Optional<WorkerResourceRecord> worker(String workerId) {
        return resourceOwner.getWorker(workerId).map(WorkerManager::toWorkerResourceRecord);
    }

    @Override
    public boolean updateWorker(WorkerDeclarationRecord worker) {
        Optional<Worker> updated = resourceOwner.updateWorker(toWorker(worker));
        updated.ifPresent(ignored -> publishWorkerRegistrySnapshot());
        return updated.isPresent();
    }

    @Override
    public boolean refreshWorkerHeartbeat(String workerId, long observedAtMillis) {
        boolean refreshed = resourceOwner.refreshWorkerHeartbeat(workerId, observedAtMillis);
        if (refreshed) {
            publishWorkerRegistrySnapshot();
        }
        return refreshed;
    }

    public boolean deleteWorker(String workerId) {
        boolean deleted = resourceOwner.deleteWorker(workerId);
        if (deleted) {
            publishWorkerRegistrySnapshot();
        }
        return deleted;
    }

    public WorkerGroupRecord upsertWorkerGroup(WorkerGroupRecord group) {
        WorkerGroupRecord record = groupOwner.upsertWorkerGroup(group);
        publishWorkerRegistrySnapshot();
        notifyDispatchWakeup("worker group declared");
        return record;
    }

    public Optional<WorkerGroupRecord> workerGroup(String groupId) {
        return groupOwner.workerGroup(groupId);
    }

    public Optional<WorkerGroupCapabilityView> workerGroupReadView(String groupId) {
        WorkerRegistrySnapshot snapshot = workerRegistrySnapshot;
        return snapshot == null ? Optional.empty() : snapshot.group(groupId).map(this::toCapabilityView);
    }

    public List<WorkerGroupRecord> workerGroups() {
        return groupOwner.workerGroups();
    }

    private WorkerGroupCapabilityView toCapabilityView(WorkerGroupRecord group) {
        return new WorkerGroupCapabilityView(
                group.groupId(),
                List.copyOf(group.projectCodes()),
                List.copyOf(group.eventCodes()),
                group.defaultAttributes(),
                group.defaultMaxConcurrentWork()
        );
    }

    public boolean deleteWorkerGroup(String groupId) {
        boolean deleted = groupOwner.deleteWorkerGroup(groupId);
        if (deleted) {
            publishWorkerRegistrySnapshot();
        }
        return deleted;
    }

    public boolean tryAcquireWorkerExclusiveLease(String workerId) {
        return admissionOwner.tryAcquireWorkerExclusiveLease(workerId);
    }

    public void releaseWorkerExclusiveLease(String workerId) {
        admissionOwner.releaseWorkerExclusiveLease(workerId);
    }

    public boolean hasWorkerExclusiveLease(String workerId) {
        return admissionOwner.hasWorkerExclusiveLease(workerId);
    }

    @Override
    public List<String> getExclusiveLeaseWorkerIds() {
        return admissionOwner.getExclusiveLeaseWorkerIds();
    }

    @Override
    public List<WorkerResourceRecord> workers() {
        return resourceOwner.getAllWorkers().stream()
                .map(WorkerManager::toWorkerResourceRecord)
                .toList();
    }

    public AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode) {
        return relationshipOwner.registerAdapterNode(adapterNode);
    }

    public Optional<AdapterNodeRecord> adapterNode(String adapterNodeId) {
        return relationshipOwner.adapterNode(adapterNodeId);
    }

    public List<AdapterNodeRecord> adapterNodes() {
        return relationshipOwner.adapterNodes();
    }

    public boolean deleteAdapterNode(String adapterNodeId) {
        return relationshipOwner.deleteAdapterNode(adapterNodeId);
    }

    public NodeGroupBindingRecord bindNodeGroup(NodeGroupBindingRecord binding) {
        return relationshipOwner.bindNodeGroup(binding);
    }

    public Optional<NodeGroupBindingRecord> nodeGroupBinding(String adapterNodeId, String groupId) {
        return relationshipOwner.nodeGroupBinding(adapterNodeId, groupId);
    }

    public List<NodeGroupBindingRecord> nodeGroupBindings() {
        return relationshipOwner.nodeGroupBindings();
    }

    public boolean unbindNodeGroup(String adapterNodeId, String groupId) {
        return relationshipOwner.unbindNodeGroup(adapterNodeId, groupId);
    }

    public Set<String> groupIdsByAdapterNodeId(String adapterNodeId) {
        return relationshipOwner.groupIdsByAdapterNodeId(adapterNodeId);
    }

    public Set<String> adapterNodeIdsByGroupId(String groupId) {
        return relationshipOwner.adapterNodeIdsByGroupId(groupId);
    }

    public NodeGroupBindingRecord setNodeGroupBindingEnabled(String adapterNodeId,
                                                             String groupId,
                                                             boolean enabled) {
        return relationshipOwner.setNodeGroupBindingEnabled(adapterNodeId, groupId, enabled);
    }

    public NodeGroupBindingRecord setNodeGroupBindingDraining(String adapterNodeId,
                                                              String groupId,
                                                              boolean draining) {
        return relationshipOwner.setNodeGroupBindingDraining(adapterNodeId, groupId, draining);
    }

    @Override
    public WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
                                                                             int maxCandidateCount) {
        return candidateSourceOwner.findWorkerCandidateBatch(selector, maxCandidateCount);
    }

    WorkerRegistrySnapshot getWorkerRegistrySnapshot() {
        return workerRegistrySnapshot;
    }

    WorkerCandidateIndex getWorkerCandidateIndex() {
        return new WorkerCandidateIndex(workerRegistrySnapshot, workerRegistry, candidateBucketPolicy);
    }

    @Override
    public void recordWarmCandidate(WorkerTaskSelector selector, WorkerCandidateRow candidate) {
        candidateSourceOwner.recordWarmCandidate(selector, candidate);
    }

    int warmCandidateCount(String taskId) {
        return candidateSourceOwner.warmCandidateCount(taskId);
    }

    void refreshWorkerRegistrySnapshot() {
        resourceOwner.syncWorkerRegistrySlots(resourceOwner.getAllWorkers());
        publishWorkerRegistrySnapshot();
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportApplication application = reportOwner.applyWorkerCapabilityReport(report);
        WorkerCapabilityReportResult result = application.result();
        if (result.snapshotChanged() && application.snapshot() != null) {
            publishWorkerRegistrySnapshot(application.snapshot());
        }
        return result;
    }

    public WorkerReachabilityState getWorkerReachability(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        return reachabilityView.getWorkerReachability(workerId);
    }

    public boolean isWorkerDispatchEnabled(String workerId) {
        return workerRegistry.isDispatchEnabled(workerId);
    }

    public boolean disableWorkerDispatch(String workerId, DispatchAvailabilitySource source, String reason) {
        return workerRegistry.disableDispatch(workerId, source);
    }

    public boolean clearWorkerDispatchDisable(String workerId, DispatchAvailabilitySource source, String reason) {
        return workerRegistry.clearDispatchDisable(workerId, source);
    }

    public void setDispatchWakeupCallback(Runnable dispatchWakeupCallback) {
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    public WorkerLoadSnapshot getWorkerLoad(String workerId) {
        return admissionOwner.getWorkerLoad(workerId);
    }

    public int getActiveWorkerCountForTask(String taskId) {
        return admissionOwner.getActiveWorkerCountForTask(taskId);
    }

    @Override
    public WorkerAdmissionResult reserveWorkerCapacity(WorkerAdmissionTarget target) {
        return admissionOwner.reserveWorkerCapacity(target);
    }

    @Override
    public boolean confirmWorkerReservation(WorkerAdmissionTarget target) {
        return admissionOwner.confirmWorkerReservation(target);
    }

    @Override
    public void releaseWorkerReservation(WorkerAdmissionTarget target) {
        admissionOwner.releaseWorkerReservation(target);
    }

    @Override
    public void recordWorkClaimed(WorkerAdmissionTarget target) {
        admissionOwner.recordWorkClaimed(target);
    }

    @Override
    public void recordWorkFinal(WorkerAdmissionTarget target) {
        admissionOwner.recordWorkFinal(target);
    }

    private void publishWorkerRegistrySnapshot() {
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    private void publishWorkerRegistrySnapshot(WorkerRegistrySnapshot snapshot) {
        WorkerRegistrySnapshot normalizedSnapshot = snapshot != null ? snapshot : WorkerRegistrySnapshot.empty();
        this.workerRegistrySnapshot = normalizedSnapshot;
    }

    private WorkerRegistrySnapshot composeWorkerRegistrySnapshot() {
        return reportOwner.composeWorkerRegistrySnapshot();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static WorkerResourceRecord toWorkerResourceRecord(Worker worker) {
        if (worker == null) {
            return null;
        }
        return new WorkerResourceRecord(
                worker.getWorkerId(),
                worker.getAgentVersion(),
                worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes()
        );
    }

    private static Worker toWorker(WorkerDeclarationRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("worker declaration record must not be null");
        }
        Worker worker = new Worker();
        worker.setWorkerId(record.workerId());
        worker.setStatus(WorkerStatus.OFFLINE);
        worker.setAgentVersion(record.agentVersion());
        worker.setWorkerGroupId(record.workerGroupId());
        worker.setOnlineStrategy(record.transportHint());
        worker.setMaxConcurrentWork(record.maxConcurrentWork());
        worker.setAttributes(record.attributes());
        worker.setCreateTime(LocalDateTime.now());
        worker.setUpdateTime(LocalDateTime.now());
        return worker;
    }

    private static WorkerStatus toWorkerStatus(String statusName) {
        String normalizedStatus = normalizeNullable(statusName);
        if (normalizedStatus == null) {
            return WorkerStatus.OFFLINE;
        }
        return WorkerStatus.valueOf(normalizedStatus);
    }

    private void notifyDispatchWakeup(String reason) {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker relationship dispatch wakeup callback failed: {}", reason, e);
        }
    }

}
