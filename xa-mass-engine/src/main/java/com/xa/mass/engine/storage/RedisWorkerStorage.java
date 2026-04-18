package com.xa.mass.engine.storage;

import com.google.gson.Gson;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.List;
import java.util.Optional;

/**
 * Redis-backed Worker storage placeholder. All methods throw {@link UnsupportedOperationException}.
 * The active mainline uses in-memory worker storage. StorageType.REDIS is not yet implemented.
 *
 * @deprecated Not implemented. Do not wire via StorageType.REDIS until this class is complete.
 */
@Deprecated
public class RedisWorkerStorage implements WorkerStorage {

    private static final String WORKER_KEY_PREFIX = "worker:";
    private static final String WORKER_CONTEXT_KEY_PREFIX = "workerContext:";
    private static final String LOCKED_WORKERS_KEY = "locked_workers";
    private static final String WORKER_GROUP_INDEX_PREFIX = "worker_group:";

    @SuppressWarnings("FieldCanBeLocal")
    private final Gson gson = new Gson();

    public RedisWorkerStorage() {
        // TODO: initialize Redis client when the Redis storage path becomes active.
    }

    @Override
    public void addWorker(Worker worker) {
        throw unsupported();
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        throw unsupported();
    }

    @Override
    public boolean updateWorker(Worker worker) {
        throw unsupported();
    }

    @Override
    public boolean deleteWorker(String workerId) {
        throw unsupported();
    }

    @Override
    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        throw unsupported();
    }

    @Override
    public List<Worker> getAllWorkers() {
        throw unsupported();
    }

    @Override
    public void addWorkerContext(WorkerContext workerContext) {
        throw unsupported();
    }

    @Override
    public List<WorkerContext> getWorkerContexts(String workerId) {
        throw unsupported();
    }

    @Override
    public Optional<WorkerContext> getWorkerContextById(String workerContextId) {
        throw unsupported();
    }

    @Override
    public boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        throw unsupported();
    }

    public boolean deleteWorkerContextById(String workerContextId) {
        throw unsupported();
    }

    @Override
    public List<WorkerContext> getAllWorkerContexts() {
        throw unsupported();
    }

    @Override
    public boolean tryLockWorker(String workerId) {
        throw unsupported();
    }

    @Override
    public void unlockWorker(String workerId) {
        throw unsupported();
    }

    @Override
    public boolean isLocked(String workerId) {
        throw unsupported();
    }

    @Override
    public List<String> getLockedWorkers() {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "Redis storage is not implemented yet. Active mainline uses InMemoryWorkerStorage. "
                        + "Prefixes reserved: "
                        + WORKER_KEY_PREFIX + ", "
                        + WORKER_CONTEXT_KEY_PREFIX + ", "
                        + LOCKED_WORKERS_KEY + ", "
                        + WORKER_GROUP_INDEX_PREFIX
        );
    }
}
