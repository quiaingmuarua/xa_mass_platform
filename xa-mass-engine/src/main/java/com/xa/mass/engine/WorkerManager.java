package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.storage.api.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Worker and workerContext access facade for the active engine runtime.
 *
 * <p>Transport reachability is read through {@link WorkerReachabilityView},
 * while the worker model remains the engine-owned control-plane record.
 */
public class WorkerManager implements WorkerLookupStore {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);

    private final WorkerStorage workerStorage;
    private final WorkerReachabilityView reachabilityView;

    public WorkerManager(WorkerStorage workerStorage) {
        this(workerStorage, WorkerReachabilityView.permissive());
    }

    public WorkerManager(WorkerStorage workerStorage, WorkerReachabilityView reachabilityView) {
        this.workerStorage = workerStorage;
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
    }

    public void addWorker(Worker worker) {
        workerStorage.addWorker(worker);
    }

    public Worker getWorker(String workerId) {
        return workerStorage.getWorker(workerId).orElse(null);
    }

    @Override
    public Worker findWorker(String workerId) {
        return getWorker(workerId);
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

    public void addWorkerContext(WorkerContext workerContext) {
        workerStorage.addWorkerContext(workerContext);
    }

    public List<WorkerContext> getWorkerContexts(String workerId) {
        return workerStorage.getWorkerContexts(workerId);
    }

    public WorkerContext getWorkerContextById(String workerContextId) {
        return workerStorage.getWorkerContextById(workerContextId).orElse(null);
    }

    public boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        return workerStorage.updateWorkerContextById(workerContextId, workerContext);
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

    public List<Worker> findWorkerCandidates(Task task) {
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        String project = task != null ? task.getProject() : null;
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            Worker targetWorker = getWorker(targetWorkerId.trim());
            return targetWorker == null ? List.of() : List.of(targetWorker);
        }
        if (eventCode != null && !eventCode.isBlank()) {
            return workerStorage.getWorkersBySupportedEventCode(eventCode);
        }
        if (project != null && !project.isBlank()) {
            return workerStorage.getWorkersBySupportedProject(project);
        }
        return workerStorage.getAllWorkers();
    }

    public List<WorkerContext> getAllWorkerContexts() {
        return workerStorage.getAllWorkerContexts();
    }

    public List<WorkerContext> getWorkerContextsByWorkerIds(List<String> workerIds) {
        return workerStorage.getWorkerContextsByWorkerIds(workerIds);
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

    public WorkerReachabilityState getWorkerReachability(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        return reachabilityView.getWorkerReachability(workerId);
    }

    public boolean isWorkerDispatchEnabled(Worker worker) {
        if (worker == null || worker.getStatus() == null) {
            return false;
        }
        return worker.getStatus() != WorkerStatus.EXPIRED;
    }

    /**
     * Legacy observer for runtime worker system events. Reachability truth now
     * lives in transport presence rather than the engine worker model.
     */
    public static class WorkerStatusEventListener {
        private final WorkerManager workerManager;

        public WorkerStatusEventListener(WorkerManager workerManager) {
            this.workerManager = workerManager;
        }

        @MassSubscribe
        public void onWorkerOnline(WorkerOnlineEvent event) {
            recordHeartbeat(event.getWorkerId());
        }

        @MassSubscribe
        public void onWorkerHeartbeat(WorkerHeartbeatEvent event) {
            recordHeartbeat(event.getWorkerId());
        }

        @MassSubscribe
        public void onWorkerOffline(WorkerOfflineEvent event) {
            log.debug("Worker offline event observed for {}", event.getWorkerId());
        }

        private void recordHeartbeat(String workerId) {
            Worker worker = workerManager.getWorker(workerId);
            if (worker == null) {
                log.debug("Ignoring heartbeat for unregistered worker {}", workerId);
                return;
            }
            worker.setLastHeartbeat(java.time.LocalDateTime.now());
            workerManager.updateWorker(worker);
        }
    }
}
