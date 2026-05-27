package com.xa.mass.engine.worker;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.load.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.storage.api.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Worker access facade for the active engine runtime.
 *
 * <p>Transport reachability is read through {@link WorkerReachabilityView},
 * while the worker model remains the engine-owned control-plane record.
 */
public class WorkerManager implements WorkerLookupStore,
        WorkerResourceRuntime,
        WorkerCandidateRuntime,
        WorkerSchedulingViewRuntime,
        WorkerAdmissionRuntime,
        WorkerReportRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);
    static final int DEFAULT_DIAGNOSTIC_CANDIDATE_LIMIT = 512;

    private final WorkerReachabilityView reachabilityView;
    private final WorkerCapabilityAuthority capabilityAuthority;
    private final WorkerRegistry workerRegistry;
    private final WorkerGroupOwner groupOwner;
    private final WorkerResourceOwner resourceOwner;
    private final WorkerCandidateSourceOwner candidateSourceOwner;
    private final WorkerAdmissionOwner admissionOwner;
    private final WorkerRelationshipOwner relationshipOwner;
    private volatile WorkerRegistrySnapshot workerRegistrySnapshot;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };

    public WorkerManager(WorkerStorage workerStorage) {
        this(workerStorage, WorkerReachabilityView.permissive());
    }

    public WorkerManager(WorkerStorage workerStorage, WorkerReachabilityView reachabilityView) {
        this(workerStorage, reachabilityView, new WorkerCapabilityAuthority());
    }

    public WorkerManager(WorkerStorage workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerRegistry workerRegistry) {
        this(workerStorage, reachabilityView, new WorkerCapabilityAuthority(), workerRegistry);
    }

    WorkerManager(WorkerStorage workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerCapabilityAuthority capabilityAuthority) {
        this(workerStorage, reachabilityView, capabilityAuthority, new InMemoryWorkerRegistry());
    }

    WorkerManager(WorkerStorage workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerCapabilityAuthority capabilityAuthority,
                  WorkerRegistry workerRegistry) {
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
        this.capabilityAuthority = capabilityAuthority != null ? capabilityAuthority : new WorkerCapabilityAuthority();
        this.workerRegistry = workerRegistry != null ? workerRegistry : new InMemoryWorkerRegistry();
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
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    public void addWorker(Worker worker) {
        resourceOwner.addWorker(worker);
        publishWorkerRegistrySnapshot();
        notifyDispatchWakeup("worker registered");
    }

    public Worker getWorker(String workerId) {
        return resourceOwner.getWorker(workerId).orElse(null);
    }

    @Override
    public Worker findWorker(String workerId) {
        return getWorker(workerId);
    }

    public boolean updateWorker(Worker worker) {
        Optional<Worker> updated = resourceOwner.updateWorker(worker);
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

    public Optional<WorkerGroupRecord> workerGroupReadView(String groupId) {
        WorkerRegistrySnapshot snapshot = workerRegistrySnapshot;
        return snapshot == null ? Optional.empty() : snapshot.group(groupId);
    }

    public List<WorkerGroupRecord> workerGroups() {
        return groupOwner.workerGroups();
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

    public List<Worker> getAllWorkers() {
        return resourceOwner.getAllWorkers();
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

    public List<Worker> findWorkerCandidates(Task task) {
        return findWorkerCandidates(WorkerTaskSelectorFactory.fromTask(task), DEFAULT_DIAGNOSTIC_CANDIDATE_LIMIT);
    }

    public List<Worker> findWorkerCandidates(Task task, int maxCandidateCount) {
        return findWorkerCandidateBatch(WorkerTaskSelectorFactory.fromTask(task), maxCandidateCount).candidates();
    }

    public List<Worker> findWorkerCandidates(WorkerTaskSelector selector) {
        return candidateSourceOwner.findWorkerCandidates(selector, DEFAULT_DIAGNOSTIC_CANDIDATE_LIMIT);
    }

    public List<Worker> findWorkerCandidates(WorkerTaskSelector selector, int maxCandidateCount) {
        return candidateSourceOwner.findWorkerCandidates(selector, maxCandidateCount);
    }

    public WorkerCandidateBatch findWorkerCandidateBatch(Task task, int maxCandidateCount) {
        return findWorkerCandidateBatch(WorkerTaskSelectorFactory.fromTask(task), maxCandidateCount);
    }

    public WorkerCandidateBatch findWorkerCandidateBatch(WorkerTaskSelector selector, int maxCandidateCount) {
        return candidateSourceOwner.findWorkerCandidateBatch(selector, maxCandidateCount);
    }

    public WorkerRegistrySnapshot getWorkerRegistrySnapshot() {
        return workerRegistrySnapshot;
    }

    public WorkerCandidateIndex getWorkerCandidateIndex() {
        return new WorkerCandidateIndex(workerRegistrySnapshot, workerRegistry);
    }

    public void recordWarmCandidate(Task task, Worker worker) {
        recordWarmCandidate(WorkerTaskSelectorFactory.fromTask(task), worker);
    }

    public void recordWarmCandidate(WorkerTaskSelector selector, Worker worker) {
        candidateSourceOwner.recordWarmCandidate(selector, worker);
    }

    int warmCandidateCount(String taskId) {
        return candidateSourceOwner.warmCandidateCount(taskId);
    }

    public void refreshWorkerRegistrySnapshot() {
        resourceOwner.syncWorkerRegistrySlots(resourceOwner.getAllWorkers());
        publishWorkerRegistrySnapshot();
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportResult result = capabilityAuthority.applyReport(
                report,
                resourceOwner.getAllWorkers(),
                groupOwner.workerGroups()
        );
        if (result.snapshotChanged() && result.snapshot() != null) {
            publishWorkerRegistrySnapshot(result.snapshot());
            resourceOwner.syncWorkerRegistrySlots(result.snapshot().workers());
        }
        return result;
    }

    public List<String> getExclusiveLeaseWorkerIds() {
        return admissionOwner.getExclusiveLeaseWorkerIds();
    }

    /**
     * Updates the engine-owned worker model status only.
     *
     * <p>This helper does not own transport reachability truth. Dispatch
     * eligibility must still read {@link #getWorkerReachability(String)}.</p>
     */
    public void updateOnlineStatus(String workerId, boolean online) {
        Worker worker = getWorker(workerId);
        if (worker == null) {
            if (!online) {
                return;
            }
            worker = new Worker();
            worker.setWorkerId(workerId);
            addWorker(worker);
        }

        worker.transitionTo(online ? WorkerStatus.ONLINE : WorkerStatus.OFFLINE);
        updateWorker(worker);
    }

    /**
     * Legacy worker-model availability helper.
     *
     * <p>This reflects {@link WorkerStatus} on the engine-owned worker record,
     * not transport presence. SDK-facing online queries should prefer the
     * transport-owned presence view when available.</p>
     */
    public boolean isWorkerOnline(String workerId) {
        Worker worker = getWorker(workerId);
        return worker != null && worker.getStatus() == WorkerStatus.ONLINE;
    }

    public WorkerReachabilityState getWorkerReachability(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        return reachabilityView.getWorkerReachability(workerId);
    }

    public boolean isWorkerDispatchEnabled(Worker worker) {
        if (worker == null || worker.getStatus() == null) {
            return false;
        }
        return worker.getStatus() != WorkerStatus.EXPIRED && isWorkerDispatchEnabled(worker.getWorkerId());
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

    public boolean tryReserveWorkerCapacity(String workerId, String taskId) {
        return admissionOwner.tryReserveWorkerCapacity(workerId, taskId);
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
        return capabilityAuthority.composeSnapshot(resourceOwner.getAllWorkers(), groupOwner.workerGroups());
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void notifyDispatchWakeup(String reason) {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker relationship dispatch wakeup callback failed: {}", reason, e);
        }
    }

    /**
     * Legacy observer for runtime worker system events. Reachability truth now
     * lives in transport presence rather than the engine worker model.
     */
    public static class WorkerStatusEventListener {
        private final WorkerManager workerManager;
        private final Runnable dispatchWakeupCallback;

        public WorkerStatusEventListener(WorkerManager workerManager) {
            this(workerManager, null);
        }

        public WorkerStatusEventListener(WorkerManager workerManager, Runnable dispatchWakeupCallback) {
            this.workerManager = workerManager;
            this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
            };
        }

        @MassSubscribe
        public void onWorkerOnline(WorkerOnlineEvent event) {
            if (recordHeartbeat(event.getWorkerId())) {
                notifyDispatchWakeup();
            }
        }

        @MassSubscribe
        public void onWorkerHeartbeat(WorkerHeartbeatEvent event) {
            recordHeartbeat(event.getWorkerId());
        }

        @MassSubscribe
        public void onWorkerOffline(WorkerOfflineEvent event) {
            log.debug("Worker offline event observed for {}", event.getWorkerId());
        }

        private boolean recordHeartbeat(String workerId) {
            Worker worker = workerManager.getWorker(workerId);
            if (worker == null) {
                log.debug("Ignoring heartbeat for unregistered worker {}", workerId);
                return false;
            }
            worker.setLastHeartbeat(java.time.LocalDateTime.now());
            workerManager.updateWorker(worker);
            return true;
        }

        private void notifyDispatchWakeup() {
            try {
                dispatchWakeupCallback.run();
            } catch (RuntimeException e) {
                log.warn("Worker online dispatch wakeup callback failed", e);
            }
        }
    }
}
