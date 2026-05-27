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
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.storage.api.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.NODE_GROUP_BINDING;

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

    private final WorkerStorage workerStorage;
    private final WorkerReachabilityView reachabilityView;
    private final WorkerCapabilityAuthority capabilityAuthority;
    private final WorkerRegistry workerRegistry;
    private final WorkerCandidateSourceOwner candidateSourceOwner;
    private final Object workerRegistryLock = new Object();
    private final LinkedHashMap<String, WorkerGroupRecord> workerGroupsById = new LinkedHashMap<>();
    private final Object adapterNodeRegistryLock = new Object();
    private final LinkedHashMap<String, AdapterNodeRecord> adapterNodesById = new LinkedHashMap<>();
    private final LinkedHashMap<NodeGroupBindingKey, NodeGroupBindingRecord> nodeGroupBindingsByKey =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> groupIdsByAdapterNodeId = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> adapterNodeIdsByGroupId = new LinkedHashMap<>();
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
        this.workerStorage = workerStorage;
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
        this.capabilityAuthority = capabilityAuthority != null ? capabilityAuthority : new WorkerCapabilityAuthority();
        this.workerRegistry = workerRegistry != null ? workerRegistry : new InMemoryWorkerRegistry();
        this.candidateSourceOwner = new WorkerCandidateSourceOwner(this::getWorkerCandidateIndex);
        for (Worker worker : workerStorage.getAllWorkers()) {
            upsertWorkerRegistrySlot(worker);
        }
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    public void addWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationRow(worker);
        workerStorage.addWorker(registrationRow);
        synchronized (workerRegistryLock) {
            upsertWorkerRegistrySlot(registrationRow);
            publishWorkerRegistrySnapshot();
        }
        applyNodeGroupBindingDispatchGate(registrationRow);
        notifyDispatchWakeup("worker registered");
    }

    public Worker getWorker(String workerId) {
        return workerStorage.getWorker(workerId).orElse(null);
    }

    @Override
    public Worker findWorker(String workerId) {
        return getWorker(workerId);
    }

    public boolean updateWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationRow(worker);
        boolean updated = workerStorage.updateWorker(registrationRow);
        if (updated) {
            synchronized (workerRegistryLock) {
                upsertWorkerRegistrySlot(registrationRow);
                publishWorkerRegistrySnapshot();
            }
            applyNodeGroupBindingDispatchGate(registrationRow);
        }
        return updated;
    }

    public boolean deleteWorker(String workerId) {
        Worker existing = getWorker(workerId);
        boolean deleted = workerStorage.deleteWorker(workerId);
        if (deleted) {
            synchronized (workerRegistryLock) {
                markWorkerRegistrySlotRemoving(existing, "worker deleted");
                publishWorkerRegistrySnapshot();
            }
        }
        return deleted;
    }

    public WorkerGroupRecord upsertWorkerGroup(WorkerGroupRecord group) {
        if (group == null) {
            throw new IllegalArgumentException("worker group must not be null");
        }
        synchronized (workerRegistryLock) {
            workerGroupsById.put(group.groupId(), group);
            publishWorkerRegistrySnapshot();
        }
        notifyDispatchWakeup("worker group declared");
        return group;
    }

    public Optional<WorkerGroupRecord> workerGroup(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return Optional.empty();
        }
        synchronized (workerRegistryLock) {
            return Optional.ofNullable(workerGroupsById.get(normalizedGroupId));
        }
    }

    public Optional<WorkerGroupRecord> workerGroupReadView(String groupId) {
        WorkerRegistrySnapshot snapshot = workerRegistrySnapshot;
        return snapshot == null ? Optional.empty() : snapshot.group(groupId);
    }

    public List<WorkerGroupRecord> workerGroups() {
        synchronized (workerRegistryLock) {
            return List.copyOf(workerGroupsById.values());
        }
    }

    public boolean deleteWorkerGroup(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return false;
        }
        synchronized (workerRegistryLock) {
            WorkerGroupRecord removed = workerGroupsById.remove(normalizedGroupId);
            if (removed != null) {
                for (String workerId : workerRegistry.workerIdsByGroupId(normalizedGroupId)) {
                    workerRegistry.markSlotRemoving(normalizedGroupId, workerId, "worker group deleted");
                }
                publishWorkerRegistrySnapshot();
                return true;
            }
            return false;
        }
    }

    public boolean tryAcquireWorkerExclusiveLease(String workerId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.tryAcquireExclusiveLease(slot.groupId(), slot.workerId()))
                .orElse(false);
    }

    public void releaseWorkerExclusiveLease(String workerId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.releaseExclusiveLease(slot.groupId(), slot.workerId()));
    }

    public boolean hasWorkerExclusiveLease(String workerId) {
        return workerRegistry.hasExclusiveLease(workerId);
    }

    public List<Worker> getAllWorkers() {
        return workerStorage.getAllWorkers();
    }

    public AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode) {
        AdapterNodeRecord record = requireAdapterNode(adapterNode);
        AdapterNodeRecord normalized;
        synchronized (adapterNodeRegistryLock) {
            AdapterNodeRecord current = adapterNodesById.get(record.adapterNodeId());
            normalized = record.withLifecycleTimestamps(
                    resolveRegisteredAt(record.registeredAt(), current == null ? null : current.registeredAt()),
                    resolveUpdatedAt(record.lastSeenAt())
            );
            adapterNodesById.put(normalized.adapterNodeId(), normalized);
        }
        if (isAdapterNodeAvailable(normalized)) {
            notifyDispatchWakeup("adapter node available");
        }
        return normalized;
    }

    public Optional<AdapterNodeRecord> adapterNode(String adapterNodeId) {
        String normalized = normalizeNullable(adapterNodeId);
        if (normalized == null) {
            return Optional.empty();
        }
        synchronized (adapterNodeRegistryLock) {
            return Optional.ofNullable(adapterNodesById.get(normalized));
        }
    }

    public List<AdapterNodeRecord> adapterNodes() {
        synchronized (adapterNodeRegistryLock) {
            return List.copyOf(adapterNodesById.values());
        }
    }

    public boolean deleteAdapterNode(String adapterNodeId) {
        String normalized = normalizeNullable(adapterNodeId);
        if (normalized == null) {
            return false;
        }
        synchronized (adapterNodeRegistryLock) {
            AdapterNodeRecord removed = adapterNodesById.remove(normalized);
            if (removed == null) {
                return false;
            }
            List<NodeGroupBindingKey> keysToRemove = nodeGroupBindingsByKey.keySet().stream()
                    .filter(key -> key.adapterNodeId().equals(normalized))
                    .toList();
            for (NodeGroupBindingKey key : keysToRemove) {
                removeBinding(key);
            }
            return true;
        }
    }

    public NodeGroupBindingRecord bindNodeGroup(NodeGroupBindingRecord binding) {
        NodeGroupBindingRecord record = requireNodeGroupBinding(binding);
        NodeGroupBindingRecord normalized;
        validateAdapterNodeRegistered(record.adapterNodeId());
        requireDeclaredWorkerGroup(record.groupId());
        synchronized (adapterNodeRegistryLock) {
            if (!adapterNodesById.containsKey(record.adapterNodeId())) {
                throw new IllegalArgumentException("adapterNodeId is not registered: " + record.adapterNodeId());
            }
            NodeGroupBindingKey key = NodeGroupBindingKey.from(record.adapterNodeId(), record.groupId());
            NodeGroupBindingRecord current = nodeGroupBindingsByKey.get(key);
            normalized = record.withLifecycleTimestamps(
                    resolveRegisteredAt(record.registeredAt(), current == null ? null : current.registeredAt()),
                    resolveUpdatedAt(record.updatedAt())
            );
            if (current != null) {
                removeBindingIndex(current);
            }
            nodeGroupBindingsByKey.put(key, normalized);
            addBindingIndex(normalized);
            applyNodeGroupBindingDispatchGate(normalized);
        }
        if (isNodeGroupBindingAvailable(normalized)) {
            notifyDispatchWakeup("node group binding available");
        }
        return normalized;
    }

    public Optional<NodeGroupBindingRecord> nodeGroupBinding(String adapterNodeId, String groupId) {
        NodeGroupBindingKey key = NodeGroupBindingKey.fromNullable(adapterNodeId, groupId);
        if (key == null) {
            return Optional.empty();
        }
        synchronized (adapterNodeRegistryLock) {
            return Optional.ofNullable(nodeGroupBindingsByKey.get(key));
        }
    }

    public List<NodeGroupBindingRecord> nodeGroupBindings() {
        synchronized (adapterNodeRegistryLock) {
            return List.copyOf(nodeGroupBindingsByKey.values());
        }
    }

    public boolean unbindNodeGroup(String adapterNodeId, String groupId) {
        NodeGroupBindingKey key = NodeGroupBindingKey.fromNullable(adapterNodeId, groupId);
        if (key == null) {
            return false;
        }
        synchronized (adapterNodeRegistryLock) {
            return removeBinding(key) != null;
        }
    }

    public Set<String> groupIdsByAdapterNodeId(String adapterNodeId) {
        String normalized = normalizeNullable(adapterNodeId);
        if (normalized == null) {
            return Set.of();
        }
        synchronized (adapterNodeRegistryLock) {
            return immutableSet(groupIdsByAdapterNodeId.get(normalized));
        }
    }

    public Set<String> adapterNodeIdsByGroupId(String groupId) {
        String normalized = normalizeNullable(groupId);
        if (normalized == null) {
            return Set.of();
        }
        synchronized (adapterNodeRegistryLock) {
            return immutableSet(adapterNodeIdsByGroupId.get(normalized));
        }
    }

    public NodeGroupBindingRecord setNodeGroupBindingEnabled(String adapterNodeId,
                                                             String groupId,
                                                             boolean enabled) {
        NodeGroupBindingRecord updated;
        boolean becameAvailable;
        synchronized (adapterNodeRegistryLock) {
            NodeGroupBindingRecord current = requireExistingBinding(adapterNodeId, groupId);
            updated = current.withEnabled(enabled, Instant.now());
            nodeGroupBindingsByKey.put(NodeGroupBindingKey.from(updated.adapterNodeId(), updated.groupId()), updated);
            applyNodeGroupBindingDispatchGate(updated);
            becameAvailable = !isNodeGroupBindingAvailable(current) && isNodeGroupBindingAvailable(updated);
        }
        if (becameAvailable) {
            notifyDispatchWakeup("node group binding enabled");
        }
        return updated;
    }

    public NodeGroupBindingRecord setNodeGroupBindingDraining(String adapterNodeId,
                                                              String groupId,
                                                              boolean draining) {
        NodeGroupBindingRecord updated;
        boolean becameAvailable;
        synchronized (adapterNodeRegistryLock) {
            NodeGroupBindingRecord current = requireExistingBinding(adapterNodeId, groupId);
            updated = current.withDraining(draining, Instant.now());
            nodeGroupBindingsByKey.put(NodeGroupBindingKey.from(updated.adapterNodeId(), updated.groupId()), updated);
            applyNodeGroupBindingDispatchGate(updated);
            becameAvailable = !isNodeGroupBindingAvailable(current) && isNodeGroupBindingAvailable(updated);
        }
        if (becameAvailable) {
            notifyDispatchWakeup("node group binding drain cleared");
        }
        return updated;
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
        synchronized (workerRegistryLock) {
            for (Worker worker : workerStorage.getAllWorkers()) {
                upsertWorkerRegistrySlot(worker);
            }
            publishWorkerRegistrySnapshot();
        }
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        synchronized (workerRegistryLock) {
            WorkerCapabilityReportResult result = capabilityAuthority.applyReport(
                    report,
                    workerStorage.getAllWorkers(),
                    workerGroupsById.values()
            );
            if (result.snapshotChanged() && result.snapshot() != null) {
                publishWorkerRegistrySnapshot(result.snapshot());
                syncWorkerRegistrySlots(result.snapshot().workers());
            }
            return result;
        }
    }

    public List<String> getExclusiveLeaseWorkerIds() {
        return workerRegistry.exclusiveLeaseWorkerIds();
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
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> new WorkerLoadSnapshot(
                        slot.workerId(),
                        slot.activeLeaseCount(),
                        slot.reservedCount(),
                        slot.declaredCapacity()
                ))
                .orElseGet(() -> WorkerLoadSnapshot.empty(workerId));
    }

    public int getActiveWorkerCountForTask(String taskId) {
        return workerRegistry.activeWorkerCountForTask(taskId);
    }

    public boolean tryReserveWorkerCapacity(String workerId, String taskId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.tryReserve(
                        slot.groupId(),
                        slot.workerId(),
                        taskId,
                        1,
                        System.currentTimeMillis()
                ).accepted())
                .orElse(false);
    }

    public boolean confirmWorkerReservation(String workerId, String taskId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.confirmReservation(slot.groupId(), slot.workerId(), taskId, 1))
                .orElse(false);
    }

    public void releaseWorkerReservation(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.releaseReservation(slot.groupId(), slot.workerId(), taskId, 1));
    }

    public void recordWorkClaimed(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.recordWorkClaimed(slot.groupId(), slot.workerId(), taskId, 1));
    }

    public void recordWorkFinal(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.recordWorkFinal(slot.groupId(), slot.workerId(), taskId, 1));
    }

    private void syncWorkerRegistrySlots(Iterable<Worker> workers) {
        if (workers == null) {
            return;
        }
        for (Worker worker : workers) {
            upsertWorkerRegistrySlot(worker);
        }
    }

    private void upsertWorkerRegistrySlot(Worker worker) {
        WorkerMeta meta = workerMeta(worker);
        if (meta == null) {
            return;
        }
        workerRegistry.upsertSlot(meta, worker.getMaxConcurrentWork(), eventBindingCeilingFor(meta.groupId()));
    }

    private void markWorkerRegistrySlotRemoving(Worker worker, String reason) {
        WorkerMeta meta = workerMeta(worker);
        if (meta == null) {
            return;
        }
        workerRegistry.markSlotRemoving(meta.groupId(), meta.workerId(), reason);
    }

    private WorkerMeta workerMeta(Worker worker) {
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        String groupId = worker == null ? null : normalizeNullable(worker.getWorkerGroupId());
        if (workerId == null || groupId == null) {
            return null;
        }
        long lastHeartbeatMillis = worker.getLastHeartbeat() == null
                ? 0L
                : worker.getLastHeartbeat().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new WorkerMeta(
                workerId,
                groupId,
                normalizeNullable(worker.getAdapterNodeId()),
                normalizeNullable(worker.getAdapterId()),
                normalizeNullable(worker.getOnlineStrategy()),
                worker.getAttributes(),
                normalizeNullable(worker.getAgentVersion()),
                null,
                lastHeartbeatMillis,
                worker.getStatus() == null ? null : worker.getStatus().name()
        );
    }

    private Set<EventKey> eventBindingCeilingFor(String groupId) {
        WorkerGroupRecord group = workerGroupsById.get(normalizeNullable(groupId));
        return group == null ? Set.of() : group.eventKeys();
    }

    private void publishWorkerRegistrySnapshot() {
        publishWorkerRegistrySnapshot(composeWorkerRegistrySnapshot());
    }

    private void publishWorkerRegistrySnapshot(WorkerRegistrySnapshot snapshot) {
        WorkerRegistrySnapshot normalizedSnapshot = snapshot != null ? snapshot : WorkerRegistrySnapshot.empty();
        this.workerRegistrySnapshot = normalizedSnapshot;
    }

    private WorkerRegistrySnapshot composeWorkerRegistrySnapshot() {
        return capabilityAuthority.composeSnapshot(workerStorage.getAllWorkers(), workerGroupsById.values());
    }

    private Worker normalizeWorkerRegistrationRow(Worker worker) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        String workerId = normalizeNullable(worker.getWorkerId());
        if (workerId == null) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        worker.setWorkerId(workerId);
        String groupId = normalizeNullable(worker.getWorkerGroupId());
        if (groupId != null) {
            worker.setWorkerGroupId(groupId);
        }
        String adapterNodeId = normalizeNullable(worker.getAdapterNodeId());
        if (adapterNodeId != null) {
            validateExplicitWorkerNodeGroupMembership(adapterNodeId, groupId);
            worker.setAdapterNodeId(adapterNodeId);
        }
        if (worker.getStatus() == WorkerStatus.ONLINE && worker.getLastHeartbeat() == null) {
            worker.setLastHeartbeat(LocalDateTime.now());
        }
        return worker;
    }

    private void validateExplicitWorkerNodeGroupMembership(String adapterNodeId, String groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("workerGroupId must not be blank when adapterNodeId is provided");
        }
        synchronized (adapterNodeRegistryLock) {
            if (!adapterNodesById.containsKey(adapterNodeId)) {
                throw new IllegalArgumentException("adapterNodeId is not registered: " + adapterNodeId);
            }
            NodeGroupBindingKey key = NodeGroupBindingKey.from(adapterNodeId, groupId);
            if (!nodeGroupBindingsByKey.containsKey(key)) {
                throw new IllegalArgumentException("node group binding is not registered: "
                        + adapterNodeId + "/" + groupId);
            }
        }
        requireDeclaredWorkerGroup(groupId);
    }

    private void validateAdapterNodeRegistered(String adapterNodeId) {
        synchronized (adapterNodeRegistryLock) {
            if (!adapterNodesById.containsKey(adapterNodeId)) {
                throw new IllegalArgumentException("adapterNodeId is not registered: " + adapterNodeId);
            }
        }
    }

    private void requireDeclaredWorkerGroup(String groupId) {
        synchronized (workerRegistryLock) {
            if (!workerGroupsById.containsKey(groupId)) {
                throw new IllegalArgumentException("workerGroupId is not declared: " + groupId);
            }
        }
    }

    private void applyNodeGroupBindingDispatchGate(Worker worker) {
        String adapterNodeId = normalizeNullable(worker.getAdapterNodeId());
        String groupId = normalizeNullable(worker.getWorkerGroupId());
        if (adapterNodeId == null || groupId == null) {
            return;
        }
        synchronized (adapterNodeRegistryLock) {
            NodeGroupBindingRecord binding = nodeGroupBindingsByKey.get(NodeGroupBindingKey.from(adapterNodeId, groupId));
            if (binding != null && (!binding.enabled() || binding.draining())) {
                disableWorkerDispatch(
                        worker.getWorkerId(),
                        NODE_GROUP_BINDING,
                        "node group binding unavailable"
                );
            } else if (binding != null) {
                clearWorkerDispatchDisable(
                        worker.getWorkerId(),
                        NODE_GROUP_BINDING,
                        "node group binding available"
                );
            }
        }
    }

    private void applyNodeGroupBindingDispatchGate(NodeGroupBindingRecord binding) {
        Set<String> workerIds = workerRegistry.workerIdsByAdapterNodeGroup(
                binding.adapterNodeId(),
                binding.groupId()
        );
        for (String workerId : workerIds) {
            if (!binding.enabled() || binding.draining()) {
                disableWorkerDispatch(
                        workerId,
                        NODE_GROUP_BINDING,
                        "node group binding unavailable"
                );
            } else {
                clearWorkerDispatchDisable(workerId, NODE_GROUP_BINDING, "node group binding available");
            }
        }
    }

    private void applyNodeGroupBindingUnavailable(NodeGroupBindingRecord binding) {
        Set<String> workerIds = workerRegistry.workerIdsByAdapterNodeGroup(
                binding.adapterNodeId(),
                binding.groupId()
        );
        for (String workerId : workerIds) {
            disableWorkerDispatch(
                    workerId,
                    NODE_GROUP_BINDING,
                    "node group binding unavailable"
            );
        }
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isAdapterNodeAvailable(AdapterNodeRecord record) {
        return record != null && record.enabled() && record.online();
    }

    private static boolean isNodeGroupBindingAvailable(NodeGroupBindingRecord binding) {
        return binding != null && binding.enabled() && !binding.draining();
    }

    private void notifyDispatchWakeup(String reason) {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker relationship dispatch wakeup callback failed: {}", reason, e);
        }
    }

    private static AdapterNodeRecord requireAdapterNode(AdapterNodeRecord adapterNode) {
        if (adapterNode == null) {
            throw new IllegalArgumentException("adapterNode must not be null");
        }
        return adapterNode;
    }

    private static NodeGroupBindingRecord requireNodeGroupBinding(NodeGroupBindingRecord binding) {
        if (binding == null) {
            throw new IllegalArgumentException("nodeGroupBinding must not be null");
        }
        return binding;
    }

    private NodeGroupBindingRecord requireExistingBinding(String adapterNodeId, String groupId) {
        NodeGroupBindingKey key = NodeGroupBindingKey.fromNullable(adapterNodeId, groupId);
        if (key == null) {
            throw new IllegalArgumentException("adapterNodeId and groupId must not be blank");
        }
        NodeGroupBindingRecord current = nodeGroupBindingsByKey.get(key);
        if (current == null) {
            throw new IllegalArgumentException("node group binding is not registered: "
                    + key.adapterNodeId() + "/" + key.groupId());
        }
        return current;
    }

    private static Instant resolveRegisteredAt(Instant requested, Instant previous) {
        if (previous != null) {
            return previous;
        }
        return requested != null ? requested : Instant.now();
    }

    private static Instant resolveUpdatedAt(Instant requested) {
        return requested != null ? requested : Instant.now();
    }

    private void addBindingIndex(NodeGroupBindingRecord binding) {
        groupIdsByAdapterNodeId.computeIfAbsent(binding.adapterNodeId(), ignored -> new LinkedHashSet<>())
                .add(binding.groupId());
        adapterNodeIdsByGroupId.computeIfAbsent(binding.groupId(), ignored -> new LinkedHashSet<>())
                .add(binding.adapterNodeId());
    }

    private NodeGroupBindingRecord removeBinding(NodeGroupBindingKey key) {
        NodeGroupBindingRecord removed = nodeGroupBindingsByKey.remove(key);
        if (removed != null) {
            applyNodeGroupBindingUnavailable(removed);
            removeBindingIndex(removed);
        }
        return removed;
    }

    private void removeBindingIndex(NodeGroupBindingRecord binding) {
        removeFromSetIndex(groupIdsByAdapterNodeId, binding.adapterNodeId(), binding.groupId());
        removeFromSetIndex(adapterNodeIdsByGroupId, binding.groupId(), binding.adapterNodeId());
    }

    private static void removeFromSetIndex(Map<String, LinkedHashSet<String>> index, String key, String value) {
        LinkedHashSet<String> values = index.get(key);
        if (values == null) {
            return;
        }
        values.remove(value);
        if (values.isEmpty()) {
            index.remove(key);
        }
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private record NodeGroupBindingKey(String adapterNodeId, String groupId) {

        private static NodeGroupBindingKey from(String adapterNodeId, String groupId) {
            return new NodeGroupBindingKey(
                    requireNonBlank(adapterNodeId, "adapterNodeId"),
                    requireNonBlank(groupId, "groupId")
            );
        }

        private static NodeGroupBindingKey fromNullable(String adapterNodeId, String groupId) {
            String normalizedAdapterNodeId = normalizeNullable(adapterNodeId);
            String normalizedGroupId = normalizeNullable(groupId);
            if (normalizedAdapterNodeId == null || normalizedGroupId == null) {
                return null;
            }
            return new NodeGroupBindingKey(normalizedAdapterNodeId, normalizedGroupId);
        }

        NodeGroupBindingKey {
            adapterNodeId = requireNonBlank(adapterNodeId, "adapterNodeId");
            groupId = requireNonBlank(groupId, "groupId");
        }

        private static String requireNonBlank(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value.trim();
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
