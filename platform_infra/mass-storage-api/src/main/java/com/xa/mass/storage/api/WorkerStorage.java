package com.xa.mass.storage.api;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for worker and worker-context state.
 *
 * <p>The active lock contract is intentionally worker-level: the lock protects
 * one active execution lane per worker in the current runtime model, even when
 * a worker owns multiple worker contexts.
 */
public interface WorkerStorage {

    void addWorker(Worker worker);

    Optional<Worker> getWorker(String workerId);

    boolean updateWorker(Worker worker);

    boolean deleteWorker(String workerId);

    List<Worker> getWorkersByGroupId(String workerGroupId);

    List<Worker> getWorkersBySupportedProject(String project);

    List<Worker> getWorkersBySupportedEventCode(String eventCode);

    List<Worker> getAllWorkers();

    void addWorkerContext(WorkerContext workerContext);

    List<WorkerContext> getWorkerContexts(String workerId);

    List<WorkerContext> getWorkerContextsByWorkerIds(List<String> workerIds);

    Optional<WorkerContext> getWorkerContextById(String workerContextId);

    boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext);

    boolean deleteWorkerContextById(String workerContextId);

    List<WorkerContext> getAllWorkerContexts();

    boolean tryLockWorker(String workerId);

    void unlockWorker(String workerId);

    boolean isLocked(String workerId);

    List<String> getLockedWorkers();
}
