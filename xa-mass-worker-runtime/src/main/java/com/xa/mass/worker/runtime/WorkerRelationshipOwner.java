package com.xa.mass.worker.runtime;

import com.xa.mass.base.model.Worker;
import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.runtime.worker.WorkerRegistry;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.NODE_GROUP_BINDING;

/**
 * Worker runtime owner for adapter-node and WorkerGroup relationship state.
 */
public final class WorkerRelationshipOwner {

    private final Object lock = new Object();
    private final LinkedHashMap<String, AdapterNodeRecord> adapterNodesById = new LinkedHashMap<>();
    private final LinkedHashMap<NodeGroupBindingKey, NodeGroupBindingRecord> nodeGroupBindingsByKey =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> groupIdsByAdapterNodeId = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> adapterNodeIdsByGroupId = new LinkedHashMap<>();
    private final WorkerRegistry workerRegistry;
    private final Predicate<String> workerGroupDeclared;
    private final Consumer<String> dispatchWakeupNotifier;

    public WorkerRelationshipOwner(WorkerRegistry workerRegistry,
                                   Predicate<String> workerGroupDeclared,
                                   Consumer<String> dispatchWakeupNotifier) {
        this.workerRegistry = workerRegistry;
        this.workerGroupDeclared = workerGroupDeclared == null ? ignored -> false : workerGroupDeclared;
        this.dispatchWakeupNotifier = dispatchWakeupNotifier == null ? ignored -> {
        } : dispatchWakeupNotifier;
    }

    public AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode) {
        AdapterNodeRecord record = requireAdapterNode(adapterNode);
        AdapterNodeRecord normalized;
        synchronized (lock) {
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
        synchronized (lock) {
            return Optional.ofNullable(adapterNodesById.get(normalized));
        }
    }

    public List<AdapterNodeRecord> adapterNodes() {
        synchronized (lock) {
            return List.copyOf(adapterNodesById.values());
        }
    }

    public boolean deleteAdapterNode(String adapterNodeId) {
        String normalized = normalizeNullable(adapterNodeId);
        if (normalized == null) {
            return false;
        }
        synchronized (lock) {
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
        synchronized (lock) {
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
        synchronized (lock) {
            return Optional.ofNullable(nodeGroupBindingsByKey.get(key));
        }
    }

    public List<NodeGroupBindingRecord> nodeGroupBindings() {
        synchronized (lock) {
            return List.copyOf(nodeGroupBindingsByKey.values());
        }
    }

    public boolean unbindNodeGroup(String adapterNodeId, String groupId) {
        NodeGroupBindingKey key = NodeGroupBindingKey.fromNullable(adapterNodeId, groupId);
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            return removeBinding(key) != null;
        }
    }

    public Set<String> groupIdsByAdapterNodeId(String adapterNodeId) {
        String normalized = normalizeNullable(adapterNodeId);
        if (normalized == null) {
            return Set.of();
        }
        synchronized (lock) {
            return immutableSet(groupIdsByAdapterNodeId.get(normalized));
        }
    }

    public Set<String> adapterNodeIdsByGroupId(String groupId) {
        String normalized = normalizeNullable(groupId);
        if (normalized == null) {
            return Set.of();
        }
        synchronized (lock) {
            return immutableSet(adapterNodeIdsByGroupId.get(normalized));
        }
    }

    public NodeGroupBindingRecord setNodeGroupBindingEnabled(String adapterNodeId,
                                                             String groupId,
                                                             boolean enabled) {
        NodeGroupBindingRecord updated;
        boolean becameAvailable;
        synchronized (lock) {
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
        synchronized (lock) {
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

    public void validateExplicitWorkerNodeGroupMembership(String adapterNodeId, String groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("workerGroupId must not be blank when adapterNodeId is provided");
        }
        synchronized (lock) {
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

    public void applyNodeGroupBindingDispatchGate(Worker worker) {
        String adapterNodeId = normalizeNullable(worker.getAdapterNodeId());
        String groupId = normalizeNullable(worker.getWorkerGroupId());
        if (adapterNodeId == null || groupId == null) {
            return;
        }
        synchronized (lock) {
            NodeGroupBindingRecord binding = nodeGroupBindingsByKey.get(NodeGroupBindingKey.from(adapterNodeId, groupId));
            if (binding != null && (!binding.enabled() || binding.draining())) {
                workerRegistry.disableDispatch(worker.getWorkerId(), NODE_GROUP_BINDING);
            } else if (binding != null) {
                workerRegistry.clearDispatchDisable(worker.getWorkerId(), NODE_GROUP_BINDING);
            }
        }
    }

    private void validateAdapterNodeRegistered(String adapterNodeId) {
        synchronized (lock) {
            if (!adapterNodesById.containsKey(adapterNodeId)) {
                throw new IllegalArgumentException("adapterNodeId is not registered: " + adapterNodeId);
            }
        }
    }

    private void requireDeclaredWorkerGroup(String groupId) {
        if (!workerGroupDeclared.test(groupId)) {
            throw new IllegalArgumentException("workerGroupId is not declared: " + groupId);
        }
    }

    private void applyNodeGroupBindingDispatchGate(NodeGroupBindingRecord binding) {
        if (!binding.enabled() || binding.draining()) {
            disableWorkerDispatchForNodeGroup(binding);
        } else {
            clearWorkerDispatchDisableForNodeGroup(binding);
        }
    }

    private void applyNodeGroupBindingUnavailable(NodeGroupBindingRecord binding) {
        disableWorkerDispatchForNodeGroup(binding);
    }

    private void disableWorkerDispatchForNodeGroup(NodeGroupBindingRecord binding) {
        workerRegistry.disableDispatchForAdapterNodeGroup(
                binding.adapterNodeId(),
                binding.groupId(),
                NODE_GROUP_BINDING
        );
    }

    private void clearWorkerDispatchDisableForNodeGroup(NodeGroupBindingRecord binding) {
        workerRegistry.clearDispatchDisableForAdapterNodeGroup(
                binding.adapterNodeId(),
                binding.groupId(),
                NODE_GROUP_BINDING
        );
    }

    private void notifyDispatchWakeup(String reason) {
        dispatchWakeupNotifier.accept(reason);
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

    private static boolean isAdapterNodeAvailable(AdapterNodeRecord record) {
        return record != null && record.enabled() && record.online();
    }

    private static boolean isNodeGroupBindingAvailable(NodeGroupBindingRecord binding) {
        return binding != null && binding.enabled() && !binding.draining();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
}
