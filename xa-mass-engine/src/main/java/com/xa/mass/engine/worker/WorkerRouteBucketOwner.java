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
    private final Map<GroupRouteBucketKey, List<String>> workerIdsByGroupRouteBucket;
    private final Map<String, Set<String>> routeBucketKeysByWorkerId;

    private WorkerRouteBucketOwner(WorkerRoutingPolicy routingPolicy,
                                   Map<GroupRouteBucketKey, List<String>> workerIdsByGroupRouteBucket,
                                   Map<String, Set<String>> routeBucketKeysByWorkerId) {
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy");
        this.workerIdsByGroupRouteBucket = immutableListMap(workerIdsByGroupRouteBucket);
        this.routeBucketKeysByWorkerId = immutableSetMap(routeBucketKeysByWorkerId);
    }

    public static WorkerRouteBucketOwner empty() {
        return new WorkerRouteBucketOwner(WorkerRoutingPolicy.defaultPolicy(), Map.of(), Map.of());
    }

    public static WorkerRouteBucketOwner fromSnapshot(WorkerRegistrySnapshot snapshot) {
        return fromSnapshot(snapshot, WorkerRoutingPolicy.defaultPolicy());
    }

    public static WorkerRouteBucketOwner fromSnapshot(WorkerRegistrySnapshot snapshot,
                                                      WorkerRoutingPolicy routingPolicy) {
        if (snapshot == null) {
            return empty();
        }
        WorkerRoutingPolicy policy = routingPolicy != null ? routingPolicy : WorkerRoutingPolicy.defaultPolicy();
        LinkedHashMap<GroupRouteBucketKey, List<String>> mutableBuckets = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> mutableWorkerBuckets = new LinkedHashMap<>();
        for (Worker worker : snapshot.workers()) {
            String workerId = normalizeNullable(worker.getWorkerId());
            String groupId = normalizeNullable(worker.getWorkerGroupId());
            if (workerId == null || groupId == null || snapshot.group(groupId).isEmpty()) {
                continue;
            }
            Set<String> routeBucketKeys = normalizeRouteKeys(policy.routeBucketKeysForWorker(worker));
            mutableWorkerBuckets.put(workerId, routeBucketKeys);
            for (String routeBucketKey : routeBucketKeys) {
                GroupRouteBucketKey key = new GroupRouteBucketKey(groupId, routeBucketKey);
                mutableBuckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(workerId);
            }
        }
        return new WorkerRouteBucketOwner(policy, mutableBuckets, mutableWorkerBuckets);
    }

    public List<String> acquireForTask(String groupId, Task task, int maxCandidateCount) {
        Set<String> routeBucketKeys = normalizeRouteKeys(routingPolicy.routeBucketKeysForTask(task));
        List<String> acquired = new ArrayList<>();
        for (String routeBucketKey : routeBucketKeys) {
            int remaining = maxCandidateCount - acquired.size();
            if (remaining <= 0) {
                break;
            }
            acquired.addAll(acquire(groupId, routeBucketKey, remaining));
        }
        return List.copyOf(acquired);
    }

    public List<String> acquire(String groupId, String routeBucketKey, int maxCandidateCount) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedRouteBucketKey = normalizeNullable(routeBucketKey);
        if (normalizedGroupId == null || normalizedRouteBucketKey == null || maxCandidateCount <= 0) {
            return List.of();
        }
        List<String> workerIds = workerIdsByGroupRouteBucket.getOrDefault(
                new GroupRouteBucketKey(normalizedGroupId, normalizedRouteBucketKey),
                List.of()
        );
        if (workerIds.size() <= maxCandidateCount) {
            return workerIds;
        }
        return List.copyOf(workerIds.subList(0, maxCandidateCount));
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

    private static Map<GroupRouteBucketKey, List<String>> immutableListMap(
            Map<GroupRouteBucketKey, List<String>> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<GroupRouteBucketKey, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<GroupRouteBucketKey, List<String>> entry : source.entrySet()) {
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
}
