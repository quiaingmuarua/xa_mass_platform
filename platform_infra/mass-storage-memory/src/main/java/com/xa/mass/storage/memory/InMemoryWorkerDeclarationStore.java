package com.xa.mass.storage.memory;

import com.xa.mass.base.model.Worker;
import com.xa.mass.storage.api.WorkerDeclarationStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory runtime worker registry backed by primary hash plus secondary indexes.
 */
public class InMemoryWorkerDeclarationStore implements WorkerDeclarationStore {

    private final Map<String, Worker> workersById = new ConcurrentHashMap<>();
    private final Map<String, String> groupIdByWorkerId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> workerIdsByGroupId = new ConcurrentHashMap<>();

    @Override
    public synchronized void addWorker(Worker worker) {
        String workerId = requireWorkerId(worker);
        unindexWorker(workerId);
        workersById.put(workerId, worker);
        indexWorker(workerId, worker.getWorkerGroupId());
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        return Optional.ofNullable(workersById.get(workerId));
    }

    @Override
    public synchronized boolean updateWorker(Worker worker) {
        String workerId = requireWorkerId(worker);
        if (!workersById.containsKey(workerId)) {
            return false;
        }
        unindexWorker(workerId);
        workersById.put(workerId, worker);
        indexWorker(workerId, worker.getWorkerGroupId());
        return true;
    }

    @Override
    public synchronized boolean deleteWorker(String workerId) {
        Worker removed = workersById.remove(workerId);
        if (removed != null) {
            unindexWorker(workerId);
        }
        return removed != null;
    }

    @Override
    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        if (workerGroupId == null) {
            return List.of();
        }
        Set<String> workerIds = workerIdsByGroupId.get(workerGroupId);
        if (workerIds == null || workerIds.isEmpty()) {
            return List.of();
        }
        List<Worker> workers = new ArrayList<>();
        for (String workerId : workerIds) {
            Worker worker = workersById.get(workerId);
            if (worker != null) {
                workers.add(worker);
            }
        }
        return workers;
    }

    @Override
    public List<Worker> getAllWorkers() {
        return new ArrayList<>(workersById.values());
    }

    private static String requireWorkerId(Worker worker) {
        Objects.requireNonNull(worker, "worker");
        return Objects.requireNonNull(worker.getWorkerId(), "workerId");
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
