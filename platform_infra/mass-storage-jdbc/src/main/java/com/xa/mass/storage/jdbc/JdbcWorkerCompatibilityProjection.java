package com.xa.mass.storage.jdbc;

import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Process-local worker/runtime residue projection used by JDBC worker storage.
 */
final class JdbcWorkerCompatibilityProjection {

    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Set<String> lockedWorkers = Collections.synchronizedSet(new HashSet<>());

    void addWorker(Worker worker) {
        workers.put(worker.getWorkerId(), worker);
    }

    Optional<Worker> getWorker(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    boolean updateWorker(Worker worker) {
        if (worker.getWorkerId() == null || !workers.containsKey(worker.getWorkerId())) {
            return false;
        }
        workers.put(worker.getWorkerId(), worker);
        return true;
    }

    boolean deleteWorker(String workerId) {
        Worker removed = workers.remove(workerId);
        if (removed != null) {
            lockedWorkers.remove(workerId);
        }
        return removed != null;
    }

    List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workers.values().stream()
                .filter(w -> workerGroupId != null && workerGroupId.equals(w.getWorkerGroupId()))
                .collect(Collectors.toList());
    }

    List<Worker> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    boolean tryLockWorker(String workerId) {
        return lockedWorkers.add(workerId);
    }

    void unlockWorker(String workerId) {
        lockedWorkers.remove(workerId);
    }

    boolean isLocked(String workerId) {
        return lockedWorkers.contains(workerId);
    }

    List<String> getLockedWorkers() {
        return new ArrayList<>(lockedWorkers);
    }
}
