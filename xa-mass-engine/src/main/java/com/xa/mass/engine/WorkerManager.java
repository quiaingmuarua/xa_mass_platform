package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.storage.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Worker and workerContext access facade for the active engine runtime.
 *
 * <p>Online truth is owned by {@link Worker#getStatus()}. This manager keeps the
 * convenience methods aligned with that single source of truth instead of
 * maintaining a second in-memory online registry.
 */
public class WorkerManager {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);

    private final WorkerStorage workerStorage;

    public WorkerManager() {
        this(TaskStorageFactory.createDefaultWorkerStorage());
    }

    public WorkerManager(WorkerStorage workerStorage) {
        this.workerStorage = workerStorage;
    }

    public void addWorker(Worker worker) {
        workerStorage.addWorker(worker);
    }

    public Worker getWorker(String workerId) {
        return workerStorage.getWorker(workerId).orElse(null);
    }

    public boolean updateWorker(Worker worker) {
        return workerStorage.updateWorker(worker);
    }

    public boolean deleteWorker(String workerId) {
        return workerStorage.deleteWorker(workerId);
    }

    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workerStorage.getWorkersByGroupId(workerGroupId);
    }

    public void addWorkerContext(String workerId, WorkerContext workerContext) {
        workerStorage.addWorkerContext(workerId, workerContext);
    }

    /**
     * Compatibility wrapper for legacy single-context callers.
     * Prefer {@link #getWorkerContexts(String)} or {@link #getWorkerContextById(String)} for new code.
     */
    public WorkerContext getWorkerContext(String workerId) {
        return workerStorage.getWorkerContext(workerId).orElse(null);
    }

    public List<WorkerContext> getWorkerContexts(String workerId) {
        return workerStorage.getWorkerContexts(workerId);
    }

    public WorkerContext getWorkerContextById(String workerContextId) {
        return workerStorage.getWorkerContextById(workerContextId).orElse(null);
    }

    public boolean updateWorkerContext(String workerId, WorkerContext workerContext) {
        return workerStorage.updateWorkerContext(workerId, workerContext);
    }

    public boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        return workerStorage.updateWorkerContextById(workerContextId, workerContext);
    }

    public boolean deleteWorkerContext(String workerId) {
        return workerStorage.deleteWorkerContext(workerId);
    }

    public boolean deleteWorkerContextById(String workerContextId) {
        return workerStorage.deleteWorkerContextById(workerContextId);
    }

    public boolean tryLockWorker(String workerId) {
        return workerStorage.tryLockWorker(workerId);
    }

    public void unlockWorker(String workerId) {
        workerStorage.unlockWorker(workerId);
    }

    public boolean isLocked(String workerId) {
        return workerStorage.isLocked(workerId);
    }

    public List<Worker> getAllWorkers() {
        return workerStorage.getAllWorkers();
    }

    public List<WorkerContext> getAllWorkerContexts() {
        return workerStorage.getAllWorkerContexts();
    }

    public List<String> getLockedWorkers() {
        return workerStorage.getLockedWorkers();
    }

    /**
     * Updates the worker model status so online checks and matching rules read a
     * single truth source.
     */
    public void updateOnlineStatus(String workerId, boolean online) {
        Worker worker = getWorker(workerId);
        if (worker == null) {
            if (!online) {
                return;
            }
            worker = new Worker();
            worker.setWorkerId(workerId);
            addWorker(worker);
        }

        worker.transitionTo(online ? WorkerStatus.ONLINE : WorkerStatus.OFFLINE);
        updateWorker(worker);
    }

    public boolean isWorkerOnline(String workerId) {
        Worker worker = getWorker(workerId);
        return worker != null && worker.getStatus() == WorkerStatus.ONLINE;
    }

    /**
     * Event listener that keeps worker model state synchronized with gateway
     * connect/disconnect events.
     */
    public static class WorkerStatusEventListener {
        private final WorkerManager workerManager;

        public WorkerStatusEventListener(WorkerManager workerManager) {
            this.workerManager = workerManager;
        }

        @com.google.common.eventbus.Subscribe
        public void onWorkerOnline(WorkerOnlineEvent event) {
            log.info("Worker online: {}", event.getWorkerId());
            String workerId = event.getWorkerId();
            Worker worker = workerManager.getWorker(workerId);
            if (worker == null) {
                worker = new Worker();
                worker.setWorkerId(workerId);
                workerManager.addWorker(worker);
            }
            worker.updateHeartbeat();
            workerManager.updateWorker(worker);
        }

        @com.google.common.eventbus.Subscribe
        public void onWorkerOffline(WorkerOfflineEvent event) {
            workerManager.updateOnlineStatus(event.getWorkerId(), false);
        }
    }
}
