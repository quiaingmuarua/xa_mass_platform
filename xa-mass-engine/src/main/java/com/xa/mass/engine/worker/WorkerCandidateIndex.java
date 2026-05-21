package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return workerForWorkerId(task, targetWorkerId).map(List::of).orElseGet(List::of);
        }

        Optional<EventKey> eventKey = eventKeyFor(task);
        if (eventKey.isPresent()) {
            return workersFor(task, eventKey.orElseThrow(), maxCandidateCount);
        }

        String projectCode = task == null ? null : normalizeNullable(task.getProject());
        if (projectCode != null) {
            return workersForProject(task, projectCode, maxCandidateCount);
        }
        return List.of();
    }

    public List<Worker> workersFor(EventKey eventKey) {
        return workersFor(null, eventKey, Integer.MAX_VALUE);
    }

    public List<Worker> workersFor(EventKey eventKey, int maxCandidateCount) {
        return workersFor(null, eventKey, maxCandidateCount);
    }

    private List<Worker> workersFor(Task task, EventKey eventKey, int maxCandidateCount) {
        if (eventKey == null) {
            return List.of();
        }
        Set<String> groupIds = snapshot.groupIdsByEventKey(eventKey);
        if (groupIds.isEmpty()) {
            return List.of();
        }

        List<Worker> workers = new ArrayList<>();
        for (String groupId : groupIds) {
            int remaining = remaining(maxCandidateCount, workers.size());
            if (remaining <= 0) {
                break;
            }
            for (String workerId : routeBucketOwner.acquireForTask(groupId, task, remaining)) {
                snapshot.worker(workerId).ifPresent(workers::add);
            }
        }
        return List.copyOf(workers);
    }

    public Optional<Worker> workerForWorkerId(Task task, String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
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

        Optional<EventKey> eventKey = eventKeyFor(task);
        if (eventKey.isPresent() && !groupSupportsEventKey(groupId.orElseThrow(), eventKey.orElseThrow())) {
            return Optional.empty();
        }
        String projectCode = task == null ? null : normalizeNullable(task.getProject());
        if (eventKey.isEmpty() && projectCode != null && !groupSupportsProject(groupId.orElseThrow(), projectCode)) {
            return Optional.empty();
        }

        return worker;
    }

    private List<Worker> workersForProject(Task task, String projectCode, int maxCandidateCount) {
        Set<String> groupIds = snapshot.groupIdsByProjectCode(projectCode);
        if (groupIds.isEmpty()) {
            return List.of();
        }

        List<Worker> workers = new ArrayList<>();
        for (String groupId : groupIds) {
            int remaining = remaining(maxCandidateCount, workers.size());
            if (remaining <= 0) {
                break;
            }
            for (String workerId : routeBucketOwner.acquireForTask(groupId, task, remaining)) {
                snapshot.worker(workerId).ifPresent(workers::add);
            }
        }
        return List.copyOf(workers);
    }

    private boolean groupSupportsEventKey(String groupId, EventKey eventKey) {
        return snapshot.groupIdsByEventKey(eventKey).contains(groupId);
    }

    private boolean groupSupportsProject(String groupId, String projectCode) {
        return snapshot.group(groupId)
                .map(group -> group.supportsProject(projectCode))
                .orElse(false);
    }

    private static int remaining(int maxCandidateCount, int currentSize) {
        if (maxCandidateCount == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxCandidateCount - currentSize);
    }

    private static Optional<EventKey> eventKeyFor(Task task) {
        if (task == null) {
            return Optional.empty();
        }
        String projectCode = normalizeNullable(task.getProject());
        String eventCode = normalizeNullable(TaskSharedConfig.sdkEventCode(task));
        if (projectCode == null || eventCode == null) {
            return Optional.empty();
        }
        return Optional.of(new EventKey(projectCode, eventCode));
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
