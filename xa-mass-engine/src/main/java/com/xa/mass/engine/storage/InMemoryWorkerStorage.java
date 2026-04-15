package com.xa.mass.engine.storage;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存 Worker 存储实现
 */
public class InMemoryWorkerStorage implements WorkerStorage {

    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Map<String, WorkerContext> workerContextMap = new ConcurrentHashMap<>();
    private final Set<String> lockedWorkers = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void addWorker(Worker worker) {
        workers.put(worker.getWorkerId(), worker);
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    @Override
    public boolean updateWorker(Worker worker) {
        if (worker.getWorkerId() == null || !workers.containsKey(worker.getWorkerId())) {
            return false;
        }
        workers.put(worker.getWorkerId(), worker);
        return true;
    }

    @Override
    public boolean deleteWorker(String workerId) {
        Worker removed = workers.remove(workerId);
        if (removed != null) {
            workerContextMap.remove(workerId);
            lockedWorkers.remove(workerId);
        }
        return removed != null;
    }

    @Override
    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workers.values().stream()
                .filter(w -> workerGroupId != null && workerGroupId.equals(w.getWorkerGroupId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Worker> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    @Override
    public void addWorkerContext(String workerId, WorkerContext workerContext) {
        workerContextMap.put(workerId, workerContext);
    }

    @Override
    public Optional<WorkerContext> getWorkerContext(String workerId) {
        return Optional.ofNullable(workerContextMap.get(workerId));
    }

    @Override
    public boolean updateWorkerContext(String workerId, WorkerContext workerContext) {
        if (!workerContextMap.containsKey(workerId)) {
            return false;
        }
        workerContextMap.put(workerId, workerContext);
        return true;
    }

    @Override
    public boolean deleteWorkerContext(String workerId) {
        return workerContextMap.remove(workerId) != null;
    }

    @Override
    public List<WorkerContext> getAllWorkerContexts() {
        return new ArrayList<>(workerContextMap.values());
    }

    @Override
    public boolean tryLockWorker(String workerId) {
        return lockedWorkers.add(workerId);
    }

    @Override
    public void unlockWorker(String workerId) {
        lockedWorkers.remove(workerId);
    }

    @Override
    public boolean isLocked(String workerId) {
        return lockedWorkers.contains(workerId);
    }

    @Override
    public List<String> getLockedWorkers() {
        return new ArrayList<>(lockedWorkers);
    }
}
