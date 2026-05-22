package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage-1 worker candidate source index backed by {@link WorkerRegistrySnapshot}.
 *
 * <p>This narrows worker rows by declared WorkerGroup capability only. It does
 * not evaluate rules, ranking, reachability, load, reservation, or worker-lock
 * policy.</p>
 */
public final class WorkerCandidateIndex {

    private final WorkerRegistrySnapshot snapshot;
    private final WorkerRouteBucketOwner routeBucketOwner;

    public WorkerCandidateIndex(WorkerRegistrySnapshot snapshot) {
        this(snapshot, WorkerRouteBucketOwner.fromSnapshot(snapshot));
    }

    public WorkerCandidateIndex(WorkerRegistrySnapshot snapshot, WorkerRouteBucketOwner routeBucketOwner) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.routeBucketOwner = routeBucketOwner != null
                ? routeBucketOwner
                : WorkerRouteBucketOwner.fromSnapshot(snapshot);
    }

    public List<Worker> workersFor(Task task) {
        return workersFor(task, Integer.MAX_VALUE);
    }

    public List<Worker> workersFor(Task task, int maxCandidateCount) {
        List<String> groupIds = TaskSharedConfig.workerGroupSelector(task);
        if (groupIds.isEmpty()) {
            return List.of();
        }

        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return workerForWorkerId(task, groupIds, targetWorkerId).map(List::of).orElseGet(List::of);
        }

        return workersForGroups(task, groupIds, maxCandidateCount);
    }

    public List<Worker> workersForGroups(Task task, List<String> groupIds, int maxCandidateCount) {
        if (groupIds == null || groupIds.isEmpty() || maxCandidateCount <= 0) {
            return List.of();
        }
        String adapterNodeId = TaskSharedConfig.adapterNodeId(task);
        List<Worker> workers = new ArrayList<>();
        for (String groupId : groupIds) {
            String normalizedGroupId = normalizeNullable(groupId);
            if (normalizedGroupId == null || snapshot.group(normalizedGroupId).isEmpty()) {
                continue;
            }
            int remaining = remaining(maxCandidateCount, workers.size());
            if (remaining <= 0) {
                break;
            }
            for (String workerId : routeBucketOwner.acquireForTask(normalizedGroupId, adapterNodeId, task, remaining)) {
                snapshot.worker(workerId).ifPresent(workers::add);
            }
        }
        return List.copyOf(workers);
    }

    public Optional<Worker> workerForWorkerId(Task task, String workerId) {
        return workerForWorkerId(task, TaskSharedConfig.workerGroupSelector(task), workerId);
    }

    private Optional<Worker> workerForWorkerId(Task task, List<String> selectedGroupIds, String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null || selectedGroupIds == null || selectedGroupIds.isEmpty()) {
            return Optional.empty();
        }

        Optional<Worker> worker = snapshot.worker(normalizedWorkerId);
        if (worker.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> groupId = snapshot.groupIdByWorkerId(normalizedWorkerId);
        if (groupId.isEmpty() || snapshot.group(groupId.orElseThrow()).isEmpty()) {
            return Optional.empty();
        }

        if (!selectedGroupIds.stream()
                .map(WorkerCandidateIndex::normalizeNullable)
                .filter(Objects::nonNull)
                .anyMatch(groupId.orElseThrow()::equals)) {
            return Optional.empty();
        }
        String adapterNodeId = TaskSharedConfig.adapterNodeId(task);
        if (adapterNodeId != null && !adapterNodeId.equals(normalizeNullable(worker.orElseThrow().getAdapterNodeId()))) {
            return Optional.empty();
        }

        return worker;
    }

    private static int remaining(int maxCandidateCount, int currentSize) {
        if (maxCandidateCount == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxCandidateCount - currentSize);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
