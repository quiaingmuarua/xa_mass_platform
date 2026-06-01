package com.xa.mass.engine;

import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine test fixture for worker declaration storage.
 */
public final class InMemoryWorkerDeclarationRuntimeStore implements WorkerDeclarationStore {

    private final Map<String, WorkerDeclarationRecord> workersById = new ConcurrentHashMap<>();
    private final Map<String, String> groupIdByWorkerId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> workerIdsByGroupId = new ConcurrentHashMap<>();

    @Override
    public synchronized void addWorker(WorkerDeclarationRecord worker) {
        String workerId = requireWorkerId(worker);
        unindexWorker(workerId);
        workersById.put(workerId, worker);
        indexWorker(workerId, worker.workerGroupId());
    }

    @Override
    public Optional<WorkerDeclarationRecord> getWorker(String workerId) {
        return Optional.ofNullable(workersById.get(workerId));
    }

    @Override
    public synchronized boolean updateWorker(WorkerDeclarationRecord worker) {
        String workerId = requireWorkerId(worker);
        if (!workersById.containsKey(workerId)) {
            return false;
        }
        unindexWorker(workerId);
        workersById.put(workerId, worker);
        indexWorker(workerId, worker.workerGroupId());
        return true;
    }

    @Override
    public synchronized boolean deleteWorker(String workerId) {
        WorkerDeclarationRecord removed = workersById.remove(workerId);
        if (removed != null) {
            unindexWorker(workerId);
        }
        return removed != null;
    }

    @Override
    public List<WorkerDeclarationRecord> getWorkersByGroupId(String workerGroupId) {
        if (workerGroupId == null) {
            return List.of();
        }
        Set<String> workerIds = workerIdsByGroupId.get(workerGroupId);
        if (workerIds == null || workerIds.isEmpty()) {
            return List.of();
        }
        List<WorkerDeclarationRecord> workers = new ArrayList<>();
        for (String workerId : workerIds) {
            WorkerDeclarationRecord worker = workersById.get(workerId);
            if (worker != null) {
                workers.add(worker);
            }
        }
        return workers;
    }

    @Override
    public List<WorkerDeclarationRecord> getAllWorkers() {
        return new ArrayList<>(workersById.values());
    }

    private static String requireWorkerId(WorkerDeclarationRecord worker) {
        Objects.requireNonNull(worker, "worker");
        return Objects.requireNonNull(worker.workerId(), "workerId");
    }

    private void indexWorker(String workerId, String workerGroupId) {
        if (workerGroupId == null) {
            groupIdByWorkerId.remove(workerId);
            return;
        }
        groupIdByWorkerId.put(workerId, workerGroupId);
        workerIdsByGroupId.computeIfAbsent(workerGroupId, ignored -> ConcurrentHashMap.newKeySet())
                .add(workerId);
    }

    private void unindexWorker(String workerId) {
        String previousGroupId = groupIdByWorkerId.remove(workerId);
        if (previousGroupId == null) {
            return;
        }
        Set<String> workerIds = workerIdsByGroupId.get(previousGroupId);
        if (workerIds == null) {
            return;
        }
        workerIds.remove(workerId);
        if (workerIds.isEmpty()) {
            workerIdsByGroupId.remove(previousGroupId, workerIds);
        }
    }
}
