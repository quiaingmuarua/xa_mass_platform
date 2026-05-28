package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.runtime.worker.WorkerRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Worker runtime owner for WorkerGroup declaration state.
 */
public final class WorkerGroupOwner {

    private final Object lock = new Object();
    private final LinkedHashMap<String, WorkerGroupRecord> workerGroupsById = new LinkedHashMap<>();
    private final WorkerRegistry workerRegistry;

    public WorkerGroupOwner(WorkerRegistry workerRegistry) {
        this.workerRegistry = workerRegistry;
    }

    public WorkerGroupRecord upsertWorkerGroup(WorkerGroupRecord group) {
        if (group == null) {
            throw new IllegalArgumentException("worker group must not be null");
        }
        synchronized (lock) {
            workerGroupsById.put(group.groupId(), group);
        }
        return group;
    }

    public Optional<WorkerGroupRecord> workerGroup(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(workerGroupsById.get(normalizedGroupId));
        }
    }

    public List<WorkerGroupRecord> workerGroups() {
        synchronized (lock) {
            return List.copyOf(workerGroupsById.values());
        }
    }

    public boolean deleteWorkerGroup(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return false;
        }
        synchronized (lock) {
            WorkerGroupRecord removed = workerGroupsById.remove(normalizedGroupId);
            if (removed == null) {
                return false;
            }
            for (String workerId : workerRegistry.workerIdsByGroupId(normalizedGroupId)) {
                workerRegistry.markSlotRemoving(normalizedGroupId, workerId, "worker group deleted");
            }
            return true;
        }
    }

    public boolean hasWorkerGroup(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return false;
        }
        synchronized (lock) {
            return workerGroupsById.containsKey(normalizedGroupId);
        }
    }

    public Set<EventKey> eventBindingCeilingFor(String groupId) {
        synchronized (lock) {
            WorkerGroupRecord group = workerGroupsById.get(normalizeNullable(groupId));
            return group == null ? Set.of() : group.eventKeys();
        }
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
