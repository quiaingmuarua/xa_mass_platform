package com.xa.mass.storage.api;

import com.xa.mass.base.model.Worker;

import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for worker state.
 *
 * <p>The active lock contract is intentionally worker-level: the lock protects
 * one active execution lane per worker in the current runtime model.
 */
public interface WorkerStorage extends WorkerLookupStore {

    void addWorker(Worker worker);

    Optional<Worker> getWorker(String workerId);

    @Override
    default Worker findWorker(String workerId) {
        return getWorker(workerId).orElse(null);
    }

    boolean updateWorker(Worker worker);

    boolean deleteWorker(String workerId);

    List<Worker> getWorkersByGroupId(String workerGroupId);

    List<Worker> getWorkersBySupportedProject(String project);

    List<Worker> getWorkersBySupportedEventCode(String eventCode);

    List<Worker> getAllWorkers();

    boolean tryLockWorker(String workerId);

    void unlockWorker(String workerId);

    boolean isLocked(String workerId);

    List<String> getLockedWorkers();
}
