package com.xa.mass.engine.storage;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.List;
import java.util.Optional;

/**
 * Worker 存储接口
 * 提供 Worker 和 WorkerContext 的存储抽象能力
 */
public interface WorkerStorage {

    void addWorker(Worker worker);
    Optional<Worker> getWorker(String workerId);
    boolean updateWorker(Worker worker);
    boolean deleteWorker(String workerId);
    List<Worker> getWorkersByGroupId(String workerGroupId);
    List<Worker> getAllWorkers();

    void addWorkerContext(String workerId, WorkerContext workerContext);
    Optional<WorkerContext> getWorkerContext(String workerId);
    boolean updateWorkerContext(String workerId, WorkerContext workerContext);
    boolean deleteWorkerContext(String workerId);
    List<WorkerContext> getAllWorkerContexts();

    boolean tryLockWorker(String workerId);
    void unlockWorker(String workerId);
    boolean isLocked(String workerId);
    List<String> getLockedWorkers();
}
