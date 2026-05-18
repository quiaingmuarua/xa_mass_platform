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

    public WorkerCandidateIndex(WorkerRegistrySnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public List<Worker> workersFor(Task task) {
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return workerForWorkerId(task, targetWorkerId).map(List::of).orElseGet(List::of);
        }

        Optional<EventKey> eventKey = eventKeyFor(task);
        if (eventKey.isPresent()) {
            return workersFor(eventKey.orElseThrow());
        }

        String projectCode = task == null ? null : normalizeNullable(task.getProject());
        if (projectCode != null) {
            return workersForProject(projectCode);
        }
        return List.of();
    }

    public List<Worker> workersFor(EventKey eventKey) {
        if (eventKey == null) {
            return List.of();
        }
        Set<String> groupIds = snapshot.groupIdsByEventKey(eventKey);
        if (groupIds.isEmpty()) {
            return List.of();
        }

        List<Worker> workers = new ArrayList<>();
        for (String groupId : groupIds) {
            for (String workerId : snapshot.workerIdsByGroupId(groupId)) {
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

    private List<Worker> workersForProject(String projectCode) {
        Set<String> groupIds = snapshot.groupIdsByProjectCode(projectCode);
        if (groupIds.isEmpty()) {
            return List.of();
        }

        List<Worker> workers = new ArrayList<>();
        for (String groupId : groupIds) {
            for (String workerId : snapshot.workerIdsByGroupId(groupId)) {
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
