package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable worker registry read snapshot for WorkerGroup candidate-source indexes.
 */
public final class WorkerRegistrySnapshot {

    private final Map<String, WorkerGroupRecord> groupsById;
    private final Map<String, Worker> workersById;
    private final Map<EventKey, Set<String>> groupIdsByEventKey;
    private final Map<String, Set<String>> groupIdsByProjectCode;
    private final Map<String, Set<String>> workerIdsByGroupId;
    private final Map<String, String> groupIdByWorkerId;
    private final Map<String, Set<String>> groupIdsByAdapterNodeId;

    private WorkerRegistrySnapshot(Map<String, WorkerGroupRecord> groupsById, Map<String, Worker> workersById) {
        this.groupsById = immutableMap(groupsById);
        this.workersById = immutableMap(workersById);
        this.groupIdsByEventKey = indexGroupsByEventKey(groupsById.values());
        this.groupIdsByProjectCode = indexGroupsByProjectCode(groupsById.values());
        this.workerIdsByGroupId = indexWorkersByGroupId(workersById.values());
        this.groupIdByWorkerId = indexGroupIdByWorkerId(workersById.values());
        this.groupIdsByAdapterNodeId = indexGroupsByAdapterNodeId(groupsById.values());
    }

    public static WorkerRegistrySnapshot empty() {
        return new WorkerRegistrySnapshot(Map.of(), Map.of());
    }

    public static WorkerRegistrySnapshot from(Collection<WorkerGroupRecord> groups, Collection<Worker> workers) {
        return new WorkerRegistrySnapshot(normalizeGroups(groups), normalizeWorkers(workers));
    }

    public WorkerRegistrySnapshot withGroup(WorkerGroupRecord group) {
        LinkedHashMap<String, WorkerGroupRecord> groups = new LinkedHashMap<>(groupsById);
        groups.put(group.groupId(), group);
        return new WorkerRegistrySnapshot(groups, workersById);
    }

    public WorkerRegistrySnapshot withoutGroup(String groupId) {
        LinkedHashMap<String, WorkerGroupRecord> groups = new LinkedHashMap<>(groupsById);
        groups.remove(normalizeNullable(groupId));
        return new WorkerRegistrySnapshot(groups, workersById);
    }

    public WorkerRegistrySnapshot withWorker(Worker worker) {
        LinkedHashMap<String, Worker> workers = new LinkedHashMap<>(workersById);
        if (worker != null && normalizeNullable(worker.getWorkerId()) != null) {
            workers.put(worker.getWorkerId().trim(), worker);
        }
        return new WorkerRegistrySnapshot(groupsById, workers);
    }

    public WorkerRegistrySnapshot withoutWorker(String workerId) {
        LinkedHashMap<String, Worker> workers = new LinkedHashMap<>(workersById);
        workers.remove(normalizeNullable(workerId));
        return new WorkerRegistrySnapshot(groupsById, workers);
    }

    public Optional<WorkerGroupRecord> group(String groupId) {
        String normalized = normalizeNullable(groupId);
        return normalized == null ? Optional.empty() : Optional.ofNullable(groupsById.get(normalized));
    }

    public Optional<Worker> worker(String workerId) {
        String normalized = normalizeNullable(workerId);
        return normalized == null ? Optional.empty() : Optional.ofNullable(workersById.get(normalized));
    }

    public Set<String> groupIdsByEventKey(EventKey eventKey) {
        return groupIdsByEventKey.getOrDefault(eventKey, Set.of());
    }

    public Set<String> groupIdsByProjectCode(String projectCode) {
        String normalized = normalizeNullable(projectCode);
        return normalized == null ? Set.of() : groupIdsByProjectCode.getOrDefault(normalized, Set.of());
    }

    public Set<String> workerIdsByGroupId(String groupId) {
        String normalized = normalizeNullable(groupId);
        return normalized == null ? Set.of() : workerIdsByGroupId.getOrDefault(normalized, Set.of());
    }

    public Optional<String> groupIdByWorkerId(String workerId) {
        String normalized = normalizeNullable(workerId);
        return normalized == null ? Optional.empty() : Optional.ofNullable(groupIdByWorkerId.get(normalized));
    }

    public Set<String> groupIdsByAdapterNodeId(String adapterNodeId) {
        String normalized = normalizeNullable(adapterNodeId);
        return normalized == null ? Set.of() : groupIdsByAdapterNodeId.getOrDefault(normalized, Set.of());
    }

    public List<WorkerGroupRecord> groups() {
        return List.copyOf(groupsById.values());
    }

    public List<Worker> workers() {
        return List.copyOf(workersById.values());
    }

    private static Map<String, WorkerGroupRecord> normalizeGroups(Collection<WorkerGroupRecord> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, WorkerGroupRecord> normalized = new LinkedHashMap<>();
        for (WorkerGroupRecord group : groups) {
            if (group != null) {
                normalized.put(group.groupId(), group);
            }
        }
        return normalized;
    }

    private static Map<String, Worker> normalizeWorkers(Collection<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Worker> normalized = new LinkedHashMap<>();
        for (Worker worker : workers) {
            String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
            if (workerId != null) {
                normalized.put(workerId, worker);
            }
        }
        return normalized;
    }

    private static Map<EventKey, Set<String>> indexGroupsByEventKey(Collection<WorkerGroupRecord> groups) {
        LinkedHashMap<EventKey, LinkedHashSet<String>> mutableIndex = new LinkedHashMap<>();
        for (WorkerGroupRecord group : groups) {
            for (EventKey key : group.eventKeys()) {
                mutableIndex.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(group.groupId());
            }
        }
        return immutableSetMap(mutableIndex);
    }

    private static Map<String, Set<String>> indexGroupsByProjectCode(Collection<WorkerGroupRecord> groups) {
        LinkedHashMap<String, LinkedHashSet<String>> mutableIndex = new LinkedHashMap<>();
        for (WorkerGroupRecord group : groups) {
            for (String projectCode : group.projectCodes()) {
                mutableIndex.computeIfAbsent(projectCode, ignored -> new LinkedHashSet<>()).add(group.groupId());
            }
        }
        return immutableSetMap(mutableIndex);
    }

    private static Map<String, Set<String>> indexWorkersByGroupId(Collection<Worker> workers) {
        LinkedHashMap<String, LinkedHashSet<String>> mutableIndex = new LinkedHashMap<>();
        for (Worker worker : workers) {
            String workerId = normalizeNullable(worker.getWorkerId());
            String groupId = normalizeNullable(worker.getWorkerGroupId());
            if (workerId != null && groupId != null) {
                mutableIndex.computeIfAbsent(groupId, ignored -> new LinkedHashSet<>()).add(workerId);
            }
        }
        return immutableSetMap(mutableIndex);
    }

    private static Map<String, String> indexGroupIdByWorkerId(Collection<Worker> workers) {
        LinkedHashMap<String, String> index = new LinkedHashMap<>();
        for (Worker worker : workers) {
            String workerId = normalizeNullable(worker.getWorkerId());
            String groupId = normalizeNullable(worker.getWorkerGroupId());
            if (workerId != null && groupId != null) {
                index.put(workerId, groupId);
            }
        }
        return immutableMap(index);
    }

    private static Map<String, Set<String>> indexGroupsByAdapterNodeId(Collection<WorkerGroupRecord> groups) {
        LinkedHashMap<String, LinkedHashSet<String>> mutableIndex = new LinkedHashMap<>();
        for (WorkerGroupRecord group : groups) {
            String adapterNodeId = normalizeNullable(group.adapterNodeId());
            if (adapterNodeId != null) {
                mutableIndex.computeIfAbsent(adapterNodeId, ignored -> new LinkedHashSet<>()).add(group.groupId());
            }
        }
        return immutableSetMap(mutableIndex);
    }

    private static <K> Map<K, Set<String>> immutableSetMap(Map<K, LinkedHashSet<String>> mutableIndex) {
        if (mutableIndex.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<K, Set<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<K, LinkedHashSet<String>> entry : mutableIndex.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return immutableMap(immutable);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
