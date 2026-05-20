package com.xa.mass.engine.worker;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.load.InMemoryWorkerLoadView;
import com.xa.mass.engine.load.WorkerLoadSnapshot;
import com.xa.mass.engine.load.WorkerLoadView;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.storage.api.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Worker access facade for the active engine runtime.
 *
 * <p>Transport reachability is read through {@link WorkerReachabilityView},
 * while the worker model remains the engine-owned control-plane record.
 */
public class WorkerManager implements WorkerLookupStore {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);

    private final WorkerStorage workerStorage;
    private final WorkerReachabilityView reachabilityView;
    private final WorkerLoadView workerLoadView;
    private final WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner;
    private final WorkerCapabilityAuthority capabilityAuthority;
    private final Object workerRegistryLock = new Object();
    private final LinkedHashMap<String, Worker> workerRegistryRows = new LinkedHashMap<>();
    private final Object adapterNodeRegistryLock = new Object();
    private final LinkedHashMap<String, AdapterNodeRecord> adapterNodesById = new LinkedHashMap<>();
    private final LinkedHashMap<NodeGroupBindingKey, NodeGroupBindingRecord> nodeGroupBindingsByKey =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> groupIdsByAdapterNodeId = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> adapterNodeIdsByGroupId = new LinkedHashMap<>();
    private volatile WorkerRegistrySnapshot workerRegistrySnapshot;

    public WorkerManager(WorkerStorage workerStorage) {
        this(workerStorage, WorkerReachabilityView.permissive(), new InMemoryWorkerLoadView());
    }

    public WorkerManager(WorkerStorage workerStorage, WorkerReachabilityView reachabilityView) {
        this(workerStorage, reachabilityView, new InMemoryWorkerLoadView());
    }

    public WorkerManager(WorkerStorage workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerLoadView workerLoadView) {
        this(workerStorage, reachabilityView, workerLoadView, new WorkerCapabilityAuthority());
    }

    public WorkerManager(WorkerStorage workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerLoadView workerLoadView,
                         WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner) {
        this(workerStorage, reachabilityView, workerLoadView, new WorkerCapabilityAuthority(),
                dispatchAvailabilityOwner);
    }

    WorkerManager(WorkerStorage workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerLoadView workerLoadView,
                  WorkerCapabilityAuthority capabilityAuthority) {
        this(workerStorage, reachabilityView, workerLoadView, capabilityAuthority,
                new WorkerDispatchAvailabilityOwner());
    }

    WorkerManager(WorkerStorage workerStorage,
                  WorkerReachabilityView reachabilityView,
                  WorkerLoadView workerLoadView,
                  WorkerCapabilityAuthority capabilityAuthority,
                  WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner) {
        this.workerStorage = workerStorage;
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
        this.workerLoadView = workerLoadView != null ? workerLoadView : new InMemoryWorkerLoadView();
        this.dispatchAvailabilityOwner = dispatchAvailabilityOwner != null
                ? dispatchAvailabilityOwner
                : new WorkerDispatchAvailabilityOwner();
        this.capabilityAuthority = capabilityAuthority != null ? capabilityAuthority : new WorkerCapabilityAuthority();
        for (Worker worker : workerStorage.getAllWorkers()) {
            putRegistryRow(worker);
        }
        this.workerRegistrySnapshot = composeWorkerRegistrySnapshot();
    }

    public void addWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationRow(worker);
        workerStorage.addWorker(registrationRow);
        syncWorkerCapacity(registrationRow);
        synchronized (workerRegistryLock) {
            putRegistryRow(registrationRow);
            publishWorkerRegistrySnapshot();
        }
        applyNodeGroupBindingDispatchGate(registrationRow);
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
            syncWorkerCapacity(registrationRow);
            synchronized (workerRegistryLock) {
                putRegistryRow(registrationRow);
                publishWorkerRegistrySnapshot();
            }
            applyNodeGroupBindingDispatchGate(registrationRow);
        }
        return updated;
    }

    public boolean deleteWorker(String workerId) {
        boolean deleted = workerStorage.deleteWorker(workerId);
        if (deleted) {
            synchronized (workerRegistryLock) {
                workerRegistryRows.remove(normalizeNullable(workerId));
                publishWorkerRegistrySnapshot();
            }
        }
        return deleted;
    }

    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workerStorage.getWorkersByGroupId(workerGroupId);
    }

    public boolean tryLockWorker(String workerId) {
        return workerStorage.tryLockWorker(workerId);
    }

    public void unlockWorker(String workerId) {
        workerStorage.unlockWorker(workerId);
    }

    public boolean isLocked(String workerId) {
        return workerStorage.isLocked(workerId);
    }

    public List<Worker> getAllWorkers() {
        return workerStorage.getAllWorkers();
    }

    public AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode) {
        AdapterNodeRecord record = requireAdapterNode(adapterNode);
        synchronized (adapterNodeRegistryLock) {
            AdapterNodeRecord current = adapterNodesById.get(record.adapterNodeId());
            AdapterNodeRecord normalized = record.withLifecycleTimestamps(
                    resolveRegisteredAt(record.registeredAt(), current == null ? null : current.registeredAt()),
                    resolveUpdatedAt(record.lastSeenAt())
            );
            adapterNodesById.put(normalized.adapterNodeId(), normalized);
            return normalized;
        }
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
        synchronized (adapterNodeRegistryLock) {
            if (!adapterNodesById.containsKey(record.adapterNodeId())) {
                throw new IllegalArgumentException("adapterNodeId is not registered: " + record.adapterNodeId());
            }
            NodeGroupBindingKey key = NodeGroupBindingKey.from(record.adapterNodeId(), record.groupId());
            NodeGroupBindingRecord current = nodeGroupBindingsByKey.get(key);
            NodeGroupBindingRecord normalized = record.withLifecycleTimestamps(
                    resolveRegisteredAt(record.registeredAt(), current == null ? null : current.registeredAt()),
                    resolveUpdatedAt(record.updatedAt())
            );
            if (current != null) {
                removeBindingIndex(current);
            }
            nodeGroupBindingsByKey.put(key, normalized);
            addBindingIndex(normalized);
            return normalized;
        }
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
        synchronized (adapterNodeRegistryLock) {
            NodeGroupBindingRecord current = requireExistingBinding(adapterNodeId, groupId);
            NodeGroupBindingRecord updated = current.withEnabled(enabled, Instant.now());
            nodeGroupBindingsByKey.put(NodeGroupBindingKey.from(updated.adapterNodeId(), updated.groupId()), updated);
            applyNodeGroupBindingDispatchGate(updated);
            return updated;
        }
    }

    public NodeGroupBindingRecord setNodeGroupBindingDraining(String adapterNodeId,
                                                              String groupId,
                                                              boolean draining) {
        synchronized (adapterNodeRegistryLock) {
            NodeGroupBindingRecord current = requireExistingBinding(adapterNodeId, groupId);
            NodeGroupBindingRecord updated = current.withDraining(draining, Instant.now());
            nodeGroupBindingsByKey.put(NodeGroupBindingKey.from(updated.adapterNodeId(), updated.groupId()), updated);
            applyNodeGroupBindingDispatchGate(updated);
            return updated;
        }
    }

    public List<Worker> findWorkerCandidates(Task task) {
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        String project = task != null ? task.getProject() : null;
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return getWorkerCandidateIndex().workersFor(task);
        }
        if (eventCode != null && !eventCode.isBlank()) {
            return getWorkerCandidateIndex().workersFor(task);
        }
        if (project != null && !project.isBlank()) {
            return getWorkerCandidateIndex().workersFor(task);
        }
        return workerStorage.getAllWorkers();
    }

    public WorkerRegistrySnapshot getWorkerRegistrySnapshot() {
        return workerRegistrySnapshot;
    }

    public WorkerCandidateIndex getWorkerCandidateIndex() {
        return new WorkerCandidateIndex(workerRegistrySnapshot);
    }

    public void refreshWorkerRegistrySnapshot() {
        synchronized (workerRegistryLock) {
            workerRegistryRows.clear();
            for (Worker worker : workerStorage.getAllWorkers()) {
                putRegistryRow(worker);
            }
            publishWorkerRegistrySnapshot();
        }
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        synchronized (workerRegistryLock) {
            WorkerCapabilityReportResult result = capabilityAuthority.applyReport(report, workerRegistryRows.values());
            if (result.snapshotChanged() && result.snapshot() != null) {
                this.workerRegistrySnapshot = result.snapshot();
            }
            return result;
        }
    }

    public List<String> getLockedWorkers() {
        return workerStorage.getLockedWorkers();
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
        return worker.getStatus() != WorkerStatus.EXPIRED
                && dispatchAvailabilityOwner.isDispatchEnabled(worker.getWorkerId());
    }

    public WorkerDispatchAvailabilityOwner getDispatchAvailabilityOwner() {
        return dispatchAvailabilityOwner;
    }

    public WorkerLoadSnapshot getWorkerLoad(String workerId) {
        syncWorkerCapacity(workerId);
        return workerLoadView.snapshot(workerId);
    }

    public int getActiveWorkerCountForTask(String taskId) {
        return workerLoadView.getActiveWorkerCountForTask(taskId);
    }

    public boolean tryReserveWorkerCapacity(String workerId, String taskId) {
        syncWorkerCapacity(workerId);
        return workerLoadView.tryReserveCapacity(workerId, taskId);
    }

    public boolean confirmWorkerReservation(String workerId, String taskId) {
        return workerLoadView.confirmReservation(workerId, taskId);
    }

    public void releaseWorkerReservation(String workerId, String taskId) {
        workerLoadView.releaseReservation(workerId, taskId);
    }

    public void recordWorkClaimed(String workerId, String taskId) {
        workerLoadView.recordWorkClaimed(workerId, taskId);
    }

    public void recordWorkFinal(String workerId, String taskId) {
        workerLoadView.recordWorkFinal(workerId, taskId);
    }

    private void syncWorkerCapacity(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        syncWorkerCapacity(getWorker(workerId));
    }

    private void syncWorkerCapacity(Worker worker) {
        if (worker == null) {
            return;
        }
        workerLoadView.recordDeclaredCapacity(worker.getWorkerId(), worker.getMaxConcurrentWork());
    }

    private void putRegistryRow(Worker worker) {
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        if (workerId != null) {
            workerRegistryRows.put(workerId, worker);
        }
    }

    private void publishWorkerRegistrySnapshot() {
        this.workerRegistrySnapshot = composeWorkerRegistrySnapshot();
    }

    private WorkerRegistrySnapshot composeWorkerRegistrySnapshot() {
        return capabilityAuthority.composeSnapshot(workerRegistryRows.values());
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
            return worker;
        }
        String compatibilityAdapterNodeId = resolveCompatibilityAdapterNodeId(worker, groupId);
        if (compatibilityAdapterNodeId != null) {
            worker.setAdapterNodeId(compatibilityAdapterNodeId);
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
    }

    private String resolveCompatibilityAdapterNodeId(Worker worker, String groupId) {
        if (groupId == null) {
            return null;
        }
        String adapterId = normalizeNullable(worker.getAdapterId());
        if (adapterId == null) {
            return null;
        }
        synchronized (adapterNodeRegistryLock) {
            NodeGroupBindingKey key = NodeGroupBindingKey.from(adapterId, groupId);
            if (nodeGroupBindingsByKey.containsKey(key)) {
                return adapterId;
            }
            AdapterNodeRecord compatibilityNode = adapterNodesById.get(adapterId);
            if (compatibilityNode == null) {
                compatibilityNode = new AdapterNodeRecord(
                        adapterId,
                        adapterId,
                        null,
                        adapterId,
                        true,
                        true,
                        Instant.now(),
                        Instant.now(),
                        Map.of("compatibilitySource", "workerRegistration")
                );
                adapterNodesById.put(adapterId, compatibilityNode);
            }
            NodeGroupBindingRecord compatibilityBinding = new NodeGroupBindingRecord(
                    adapterId,
                    groupId,
                    null,
                    null,
                    true,
                    false,
                    Instant.now(),
                    Instant.now(),
                    Map.of("compatibilitySource", "workerRegistration")
            );
            nodeGroupBindingsByKey.put(key, compatibilityBinding);
            addBindingIndex(compatibilityBinding);
            log.debug("Resolved legacy worker registration {} to compatibility adapterNodeId {} and group {}",
                    worker.getWorkerId(), adapterId, groupId);
            return adapterId;
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
                dispatchAvailabilityOwner.disableForDraining(worker.getWorkerId(), "node group binding unavailable");
            }
        }
    }

    private void applyNodeGroupBindingDispatchGate(NodeGroupBindingRecord binding) {
        Set<String> workerIds = workerRegistrySnapshot.workerIdsByAdapterNodeGroup(
                binding.adapterNodeId(),
                binding.groupId()
        );
        for (String workerId : workerIds) {
            if (!binding.enabled() || binding.draining()) {
                dispatchAvailabilityOwner.disableForDraining(workerId, "node group binding unavailable");
            } else {
                dispatchAvailabilityOwner.enable(workerId, "node group binding available");
            }
        }
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

        public WorkerStatusEventListener(WorkerManager workerManager) {
            this.workerManager = workerManager;
        }

        @MassSubscribe
        public void onWorkerOnline(WorkerOnlineEvent event) {
            recordHeartbeat(event.getWorkerId());
        }

        @MassSubscribe
        public void onWorkerHeartbeat(WorkerHeartbeatEvent event) {
            recordHeartbeat(event.getWorkerId());
        }

        @MassSubscribe
        public void onWorkerOffline(WorkerOfflineEvent event) {
            log.debug("Worker offline event observed for {}", event.getWorkerId());
        }

        private void recordHeartbeat(String workerId) {
            Worker worker = workerManager.getWorker(workerId);
            if (worker == null) {
                log.debug("Ignoring heartbeat for unregistered worker {}", workerId);
                return;
            }
            worker.setLastHeartbeat(java.time.LocalDateTime.now());
            workerManager.updateWorker(worker);
        }
    }
}
