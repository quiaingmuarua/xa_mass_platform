package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owner for bounded Stage-1 worker route buckets.
 *
 * <p>The first implementation is an immutable in-memory index built from the
 * worker registry snapshot. It indexes relation candidates only; reachability,
 * dispatch gates, load, and lock admission remain Stage-2 owner truth.</p>
 */
public final class WorkerRouteBucketOwner {

    private final WorkerRoutingPolicy routingPolicy;
    private final WorkerRouteBucketSelectionPolicy selectionPolicy;
    private final Map<GroupRouteBucketKey, List<String>> workerIdsByGroupRouteBucket;
    private final Map<NodeGroupRouteBucketKey, List<String>> workerIdsByNodeGroupRouteBucket;
    private final Map<String, Set<String>> routeBucketKeysByWorkerId;

    private WorkerRouteBucketOwner(WorkerRoutingPolicy routingPolicy,
                                   WorkerRouteBucketSelectionPolicy selectionPolicy,
                                   Map<GroupRouteBucketKey, List<String>> workerIdsByGroupRouteBucket,
                                   Map<NodeGroupRouteBucketKey, List<String>> workerIdsByNodeGroupRouteBucket,
                                   Map<String, Set<String>> routeBucketKeysByWorkerId) {
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy");
        this.selectionPolicy = Objects.requireNonNull(selectionPolicy, "selectionPolicy");
        this.workerIdsByGroupRouteBucket = immutableListMap(workerIdsByGroupRouteBucket);
        this.workerIdsByNodeGroupRouteBucket = immutableListMap(workerIdsByNodeGroupRouteBucket);
        this.routeBucketKeysByWorkerId = immutableSetMap(routeBucketKeysByWorkerId);
    }

    public static WorkerRouteBucketOwner empty() {
        return new WorkerRouteBucketOwner(
                WorkerRoutingPolicy.defaultPolicy(),
                RandomWorkerRouteBucketSelectionPolicy.defaultPolicy(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    public static WorkerRouteBucketOwner fromSnapshot(WorkerRegistrySnapshot snapshot) {
        return fromSnapshot(snapshot, WorkerRoutingPolicy.defaultPolicy());
    }

    public static WorkerRouteBucketOwner fromSnapshot(WorkerRegistrySnapshot snapshot,
                                                      WorkerRoutingPolicy routingPolicy) {
        return fromSnapshot(snapshot, routingPolicy, RandomWorkerRouteBucketSelectionPolicy.defaultPolicy());
    }

    public static WorkerRouteBucketOwner fromSnapshot(WorkerRegistrySnapshot snapshot,
                                                      WorkerRoutingPolicy routingPolicy,
                                                      WorkerRouteBucketSelectionPolicy selectionPolicy) {
        if (snapshot == null) {
            return empty();
        }
        WorkerRoutingPolicy policy = routingPolicy != null ? routingPolicy : WorkerRoutingPolicy.defaultPolicy();
        WorkerRouteBucketSelectionPolicy selector = selectionPolicy != null
                ? selectionPolicy
                : RandomWorkerRouteBucketSelectionPolicy.defaultPolicy();
        LinkedHashMap<GroupRouteBucketKey, List<String>> mutableBuckets = new LinkedHashMap<>();
        LinkedHashMap<NodeGroupRouteBucketKey, List<String>> mutableNodeBuckets = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> mutableWorkerBuckets = new LinkedHashMap<>();
        for (Worker worker : snapshot.workers()) {
            String workerId = normalizeNullable(worker.getWorkerId());
            String groupId = normalizeNullable(worker.getWorkerGroupId());
            if (workerId == null || groupId == null || snapshot.group(groupId).isEmpty()) {
                continue;
            }
            String adapterNodeId = normalizeNullable(worker.getAdapterNodeId());
            Set<String> routeBucketKeys = normalizeRouteKeys(policy.routeBucketKeysForWorker(worker));
            mutableWorkerBuckets.put(workerId, routeBucketKeys);
            for (String routeBucketKey : routeBucketKeys) {
                GroupRouteBucketKey key = new GroupRouteBucketKey(groupId, routeBucketKey);
                mutableBuckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(workerId);
                if (adapterNodeId != null) {
                    NodeGroupRouteBucketKey nodeKey =
                            new NodeGroupRouteBucketKey(groupId, adapterNodeId, routeBucketKey);
                    mutableNodeBuckets.computeIfAbsent(nodeKey, ignored -> new ArrayList<>()).add(workerId);
                }
            }
        }
        return new WorkerRouteBucketOwner(policy, selector, mutableBuckets, mutableNodeBuckets, mutableWorkerBuckets);
    }

    public List<String> acquireForTask(String groupId, Task task, int maxCandidateCount) {
        return acquireForTask(groupId, null, task, maxCandidateCount);
    }

    public List<String> acquireForTask(String groupId, String adapterNodeId, Task task, int maxCandidateCount) {
        Set<String> routeBucketKeys = normalizeRouteKeys(routingPolicy.routeBucketKeysForTask(task));
        List<String> acquired = new ArrayList<>();
        for (String routeBucketKey : routeBucketKeys) {
            int remaining = maxCandidateCount - acquired.size();
            if (remaining <= 0) {
                break;
            }
            acquired.addAll(acquire(groupId, adapterNodeId, routeBucketKey, remaining));
        }
        return List.copyOf(acquired);
    }

    public List<String> acquire(String groupId, String routeBucketKey, int maxCandidateCount) {
        return acquire(groupId, null, routeBucketKey, maxCandidateCount);
    }

    public List<String> acquire(String groupId, String adapterNodeId, String routeBucketKey, int maxCandidateCount) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedAdapterNodeId = normalizeNullable(adapterNodeId);
        String normalizedRouteBucketKey = normalizeNullable(routeBucketKey);
        if (normalizedGroupId == null || normalizedRouteBucketKey == null || maxCandidateCount <= 0) {
            return List.of();
        }
        List<String> workerIds;
        if (normalizedAdapterNodeId == null) {
            workerIds = workerIdsByGroupRouteBucket.getOrDefault(
                    new GroupRouteBucketKey(normalizedGroupId, normalizedRouteBucketKey),
                    List.of()
            );
        } else {
            workerIds = workerIdsByNodeGroupRouteBucket.getOrDefault(
                    new NodeGroupRouteBucketKey(normalizedGroupId, normalizedAdapterNodeId, normalizedRouteBucketKey),
                    List.of()
            );
        }
        return selectionPolicy.select(
                new WorkerRouteBucketSelectionContext(
                        normalizedGroupId,
                        normalizedAdapterNodeId,
                        normalizedRouteBucketKey
                ),
                workerIds,
                maxCandidateCount
        );
    }

    public Set<String> routeBucketKeysByWorkerId(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return Set.of();
        }
        return routeBucketKeysByWorkerId.getOrDefault(normalizedWorkerId, Set.of());
    }

    private static Set<String> normalizeRouteKeys(Collection<String> routeBucketKeys) {
        if (routeBucketKeys == null || routeBucketKeys.isEmpty()) {
            return Set.of(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String routeBucketKey : routeBucketKeys) {
            String value = normalizeNullable(routeBucketKey);
            if (value != null) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static <K> Map<K, List<String>> immutableListMap(Map<K, List<String>> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<K, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<K, List<String>> entry : source.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Set<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record GroupRouteBucketKey(String groupId, String routeBucketKey) {
    }

    private record NodeGroupRouteBucketKey(String groupId, String adapterNodeId, String routeBucketKey) {
    }
}
