package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.AdapterNodeRecord;
import com.xa.mass.runtime.worker.NodeGroupBindingRecord;
import com.xa.mass.runtime.worker.WorkerGroupRecord;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.ReserveResult;
import com.xa.mass.runtime.worker.WorkerAdmissionRuntime;
import com.xa.mass.runtime.worker.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.runtime.worker.WorkerCandidateBatch;
import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerCandidateRuntime;
import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;
import com.xa.mass.runtime.worker.WorkerCapabilityReport;
import com.xa.mass.runtime.worker.WorkerDispatchGateRuntime;
import com.xa.mass.runtime.worker.WorkerGroupCapabilityView;
import com.xa.mass.runtime.worker.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.WorkerReachabilityState;
import com.xa.mass.runtime.worker.WorkerReachabilityView;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerReportRuntime;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import com.xa.mass.runtime.worker.WorkerSchedulingViewRuntime;
import com.xa.mass.runtime.worker.WorkerTaskSelector;
import com.xa.mass.runtime.worker.WorkerWarmHintRuntime;
import com.xa.mass.storage.api.WorkerStorage;
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
import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
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
 * <p>Transport reachability is read through {@link WorkerReachabilityView},
 * while the worker model remains the engine-owned control-plane record.
 */
public class WorkerManager implements WorkerResourceRuntime,
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
    private final WorkerGroupOwner groupOwner;
    private final WorkerResourceOwner resourceOwner;
    private final WorkerReportOwner reportOwner;
    private final WorkerCandidateSourceOwner candidateSourceOwner;
    private final WorkerAdmissionOwner admissionOwner;
    private final WorkerRelationshipOwner relationshipOwner;
    private volatile WorkerRegistrySnapshot workerRegistrySnapshot;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };

    public WorkerManager(WorkerStorage workerStorage,
                         WorkerRegistry workerRegistry) {
        this(workerStorage, WorkerReachabilityView.permissive(), workerRegistry);
    }

    public WorkerManager(WorkerStorage workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerRegistry workerRegistry) {
        this(workerStorage, reachabilityView, new WorkerCapabilityAuthority(), workerRegistry);
    }

    WorkerManager(WorkerStorage workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerCapabilityAuthority capabilityAuthority,
                  WorkerRegistry workerRegistry) {
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.groupOwner = new WorkerGroupOwner(this.workerRegistry);
        this.candidateSourceOwner = new WorkerCandidateSourceOwner(this::getWorkerCandidateIndex);
        this.admissionOwner = new WorkerAdmissionOwner(this.workerRegistry);
        this.relationshipOwner = new WorkerRelationshipOwner(
                this.workerRegistry,
                this.groupOwner::hasWorkerGroup,
                this::notifyDispatchWakeup
        );
        this.resourceOwner = new WorkerResourceOwner(
                workerStorage,
                this.workerRegistry,
                this.groupOwner,
                this.relationshipOwner
        );
        this.resourceOwner.syncWorkerRegistrySlots(this.resourceOwner.getAllWorkers());
        this.reportOwner = new WorkerReportOwner(capabilityAuthority, this.resourceOwner, this.groupOwner);
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    @Override
    public void addWorker(WorkerResourceRecord worker) {
        resourceOwner.addWorker(toWorker(worker));
        publishWorkerRegistrySnapshot();
        notifyDispatchWakeup("worker registered");
    }

    @Override
    public Optional<WorkerResourceRecord> worker(String workerId) {
        return resourceOwner.getWorker(workerId).map(WorkerManager::toWorkerResourceRecord);
    }

    @Override
    public boolean updateWorker(WorkerResourceRecord worker) {
        Optional<Worker> updated = resourceOwner.updateWorker(toWorker(worker));
        updated.ifPresent(ignored -> publishWorkerRegistrySnapshot());
        return updated.isPresent();
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

    public WorkerRegistrySnapshot getWorkerRegistrySnapshot() {
        return workerRegistrySnapshot;
    }

    public WorkerCandidateIndex getWorkerCandidateIndex() {
        return new WorkerCandidateIndex(workerRegistrySnapshot, workerRegistry);
    }

    @Override
    public void recordWarmCandidate(WorkerTaskSelector selector, WorkerCandidateRow candidate) {
        candidateSourceOwner.recordWarmCandidate(selector, candidate);
    }

    int warmCandidateCount(String taskId) {
        return candidateSourceOwner.warmCandidateCount(taskId);
    }

    public void refreshWorkerRegistrySnapshot() {
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
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> !slot.removing() && slot.dispatchEnabled())
                .orElse(false);
    }

    public boolean disableWorkerDispatch(String workerId, DispatchAvailabilitySource source, String reason) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.disableDispatch(slot.groupId(), slot.workerId(), source))
                .orElse(false);
    }

    public boolean clearWorkerDispatchDisable(String workerId, DispatchAvailabilitySource source, String reason) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.clearDispatchDisable(slot.groupId(), slot.workerId(), source))
                .orElse(false);
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
    public ReserveResult reserveWorkerCapacity(String workerId, String taskId) {
        return admissionOwner.reserveWorkerCapacity(workerId, taskId);
    }

    public boolean confirmWorkerReservation(String workerId, String taskId) {
        return admissionOwner.confirmWorkerReservation(workerId, taskId);
    }

    public void releaseWorkerReservation(String workerId, String taskId) {
        admissionOwner.releaseWorkerReservation(workerId, taskId);
    }

    public void recordWorkClaimed(String workerId, String taskId) {
        admissionOwner.recordWorkClaimed(workerId, taskId);
    }

    public void recordWorkFinal(String workerId, String taskId) {
        admissionOwner.recordWorkFinal(workerId, taskId);
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
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        );
    }

    private static Worker toWorker(WorkerResourceRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("worker resource record must not be null");
        }
        Worker worker = new Worker();
        worker.setWorkerId(record.workerId());
        worker.setStatus(toWorkerStatus(record.statusName()));
        worker.setAgentVersion(record.agentVersion());
        worker.setLastHeartbeat(record.lastHeartbeat());
        worker.setSupportedProjects(record.supportedProjects());
        worker.setSupportedEventCodes(record.supportedEventCodes());
        worker.setWorkerGroupId(record.workerGroupId());
        worker.setAdapterNodeId(record.adapterNodeId());
        worker.setAdapterId(record.adapterId());
        worker.setOnlineStrategy(record.onlineStrategy());
        worker.setMaxConcurrentWork(record.maxConcurrentWork());
        worker.setAttributes(record.attributes());
        worker.setCreateTime(record.createTime() != null ? record.createTime() : LocalDateTime.now());
        worker.setUpdateTime(record.updateTime() != null ? record.updateTime() : LocalDateTime.now());
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
