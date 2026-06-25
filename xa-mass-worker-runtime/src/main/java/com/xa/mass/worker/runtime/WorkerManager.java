package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;

import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchRecoveryMode;
import com.xa.mass.worker.runtime.control.WorkerDispatchRecoveryRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerGroupCapabilityView;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.worker.runtime.report.WorkerReportRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;
import com.xa.mass.worker.runtime.resource.WorkerNodeBindingRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceDeclarationRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.selection.SelectedWorkerEvidence;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionOwner;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRequest;
import com.xa.mass.worker.runtime.selection.WorkerSelectionResult;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.worker.runtime.WorkerAdmissionOwner;
import com.xa.mass.worker.runtime.WorkerCapabilityAuthority;
import com.xa.mass.worker.runtime.WorkerCapabilityReportApplication;
import com.xa.mass.worker.runtime.WorkerGroupOwner;
import com.xa.mass.worker.runtime.WorkerRelationshipOwner;
import com.xa.mass.worker.runtime.WorkerReportOwner;
import com.xa.mass.worker.runtime.WorkerResourceOwner;
import com.xa.mass.worker.runtime.WorkerRegistrySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Worker access facade for the active engine runtime.
 *
 * <p>Reachability is exposed as a point read for diagnostics. Endpoint leases
 * stay in transport delivery and must not be promoted into worker lifecycle
 * truth.
 */
public class WorkerManager implements WorkerResourceQueryRuntime,
        WorkerResourceDeclarationRuntime,
        WorkerNodeBindingRuntime,
        WorkerHeartbeatRuntime,
        WorkerSchedulingViewRuntime,
        WorkerAdmissionRuntime,
        WorkerSelectionRuntime,
        WorkerAvailabilityWakeupRuntime,
        WorkerDispatchBlockRuntime,
        WorkerDispatchGateRuntime,
        WorkerDispatchRecoveryRuntime,
        WorkerReportRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);
    private final Function<String, WorkerReachabilityState> reachabilityLookup;
    private final WorkerRegistry workerRegistry;
    private final WorkerScoreBandSlotRuntime scoreBandSlotRuntime;
    private final WorkerGroupOwner groupOwner;
    private final WorkerResourceOwner resourceOwner;
    private final WorkerReportOwner reportOwner;
    private final WorkerAdmissionOwner admissionOwner;
    private final WorkerSelectionOwner selectionOwner;
    private final WorkerRelationshipOwner relationshipOwner;
    private volatile WorkerRegistrySnapshot workerRegistrySnapshot;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };

    public WorkerManager(WorkerDeclarationStore workerStorage,
                         WorkerRegistry workerRegistry,
                         WorkerScoreBandSlotRuntime scoreBandSlotRuntime) {
        this(workerStorage, permissiveReachability(), workerRegistry, scoreBandSlotRuntime);
    }

    public WorkerManager(WorkerDeclarationStore workerStorage,
                         Function<String, WorkerReachabilityState> reachabilityLookup,
                         WorkerRegistry workerRegistry,
                         WorkerScoreBandSlotRuntime scoreBandSlotRuntime) {
        this(workerStorage,
                reachabilityLookup,
                new WorkerCapabilityAuthority(),
                workerRegistry,
                scoreBandSlotRuntime);
    }

    WorkerManager(WorkerDeclarationStore workerStorage,
                  Function<String, WorkerReachabilityState> reachabilityLookup,
                  WorkerCapabilityAuthority capabilityAuthority,
                  WorkerRegistry workerRegistry,
                  WorkerScoreBandSlotRuntime scoreBandSlotRuntime) {
        this.reachabilityLookup = reachabilityLookup != null ? reachabilityLookup : permissiveReachability();
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.scoreBandSlotRuntime = Objects.requireNonNull(scoreBandSlotRuntime, "scoreBandSlotRuntime");
        this.groupOwner = new WorkerGroupOwner(this.workerRegistry);
        this.admissionOwner = new WorkerAdmissionOwner(this.workerRegistry);
        this.selectionOwner = new WorkerSelectionOwner(this, this, this.scoreBandSlotRuntime);
        this.relationshipOwner = new WorkerRelationshipOwner(this.groupOwner::hasWorkerGroup);
        this.resourceOwner = new WorkerResourceOwner(
                workerStorage,
                this.workerRegistry,
                this.scoreBandSlotRuntime,
                this.groupOwner
        );
        this.resourceOwner.syncWorkerRegistrySlots(this.resourceOwner.getAllWorkers());
        this.reportOwner = new WorkerReportOwner(capabilityAuthority, this.resourceOwner, this.groupOwner);
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    @Override
    public void addWorker(WorkerDeclarationRecord worker) {
        resourceOwner.addWorker(worker);
        publishWorkerRegistrySnapshot();
        notifyDispatchWakeup("worker registered");
    }

    @Override
    public Optional<WorkerResourceRecord> worker(String workerId) {
        return resourceOwner.getWorker(workerId).map(WorkerManager::toWorkerResourceRecord);
    }

    @Override
    public boolean updateWorker(WorkerDeclarationRecord worker) {
        Optional<WorkerDeclarationRecord> updated = resourceOwner.updateWorker(worker);
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
        if (workerId != null && !workerId.isBlank()) {
            notifyDispatchWakeup("worker exclusive lease released");
        }
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
    public WorkerSelectionResult selectAndReserve(WorkerSelectionRequest request) {
        return selectionOwner.selectAndReserve(request);
    }

    @Override
    public boolean confirmSelected(SelectedWorkerHandle handle) {
        return selectionOwner.confirmSelected(handle);
    }

    @Override
    public void releaseSelected(SelectedWorkerHandle handle) {
        selectionOwner.releaseSelected(handle);
    }

    @Override
    public void releaseSelected(SelectedWorkerEvidence evidence) {
        selectionOwner.releaseSelected(evidence);
    }

    @Override
    public void recordSelectedClaimed(SelectedWorkerHandle handle) {
        selectionOwner.recordSelectedClaimed(handle);
    }

    @Override
    public void recordSelectedFinal(SelectedWorkerEvidence evidence) {
        selectionOwner.recordSelectedFinal(evidence);
    }

    @Override
    public void releaseSelectedLock(SelectedWorkerHandle handle) {
        selectionOwner.releaseSelectedLock(handle);
    }

    @Override
    public void releaseSelectedLock(SelectedWorkerEvidence evidence) {
        selectionOwner.releaseSelectedLock(evidence);
    }

    WorkerRegistrySnapshot getWorkerRegistrySnapshot() {
        return workerRegistrySnapshot;
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
        WorkerReachabilityState state = reachabilityLookup.apply(workerId.trim());
        return state != null ? state : WorkerReachabilityState.UNKNOWN;
    }

    private static Function<String, WorkerReachabilityState> permissiveReachability() {
        return workerId -> WorkerReachabilityState.ONLINE;
    }

    public boolean isWorkerDispatchEnabled(String workerId) {
        return workerRegistry.isDispatchEnabled(workerId);
    }

    public boolean disableWorkerDispatch(String workerId, DispatchAvailabilitySource source, String reason) {
        return workerRegistry.disableDispatch(workerId, source);
    }

    @Override
    public boolean blockWorkerDispatch(String workerId, WorkerDispatchBlockSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return workerRegistry.blockDispatch(workerId, blockRecord(signal));
    }

    @Override
    public boolean blockWorkerDispatch(String workerGroupId, String workerId, WorkerDispatchBlockSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return workerRegistry.blockDispatch(workerGroupId, workerId, blockRecord(signal));
    }

    Optional<WorkerDispatchBlockRecord> dispatchBlockRecord(String workerGroupId,
                                                            String workerId,
                                                            DispatchAvailabilitySource source) {
        return workerRegistry.dispatchBlockRecord(workerGroupId, workerId, source);
    }

    public boolean clearWorkerDispatchDisable(String workerId, DispatchAvailabilitySource source, String reason) {
        return workerRegistry.clearDispatchDisable(workerId, source);
    }

    @Override
    public boolean recoverWorkerDispatch(String workerId, DispatchAvailabilitySource source, String reason) {
        Objects.requireNonNull(source, "source");
        Optional<com.xa.mass.runtime.worker.WorkerMeta> meta = workerRegistry.workerMeta(workerId);
        if (meta.isEmpty()) {
            return false;
        }
        if (!recoveryAllowed(meta.orElseThrow(), source)) {
            return false;
        }
        return workerRegistry.recoverDispatchDisable(workerId, source);
    }

    public void setDispatchWakeupCallback(Runnable dispatchWakeupCallback) {
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    public WorkerLoadSnapshot getWorkerLoad(String workerId) {
        return admissionOwner.getWorkerLoad(workerId);
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

    private static WorkerResourceRecord toWorkerResourceRecord(WorkerDeclarationRecord worker) {
        if (worker == null) {
            return null;
        }
        return new WorkerResourceRecord(
                worker.workerId(),
                worker.agentVersion(),
                worker.workerGroupId(),
                worker.transportHint(),
                worker.maxConcurrentWork(),
                worker.attributes()
        );
    }

    private void notifyDispatchWakeup(String reason) {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker relationship dispatch wakeup callback failed: {}", reason, e);
        }
    }

    private static WorkerDispatchBlockRecord blockRecord(WorkerDispatchBlockSignal signal) {
        return new WorkerDispatchBlockRecord(
                signal.source().gateSource(),
                signal.reason(),
                signal.observedAtMillis(),
                signal.suggestedRecheckAfterMillis()
        );
    }

    private static boolean recoveryAllowed(com.xa.mass.runtime.worker.WorkerMeta meta,
                                           DispatchAvailabilitySource source) {
        if (controlledRecoverySource(source)) {
            return true;
        }
        return WorkerDispatchRecoveryMode.fromAttributes(meta.attributes())
                == WorkerDispatchRecoveryMode.FRESHNESS_EVIDENCE;
    }

    private static boolean controlledRecoverySource(DispatchAvailabilitySource source) {
        return source == DispatchAvailabilitySource.WORKER_STATE
                || source == DispatchAvailabilitySource.WORKER_COMMAND
                || source == DispatchAvailabilitySource.NODE_GROUP_BINDING;
    }

}
