package com.xa.mass.engine.worker;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.load.InMemoryWorkerLoadView;
import com.xa.mass.engine.load.WorkerLoadSnapshot;
import com.xa.mass.engine.load.WorkerLoadView;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.storage.api.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Worker access facade for the active engine runtime.
 *
 * <p>Transport reachability is read through {@link WorkerReachabilityView},
 * while the worker model remains the engine-owned control-plane record.
 */
public class WorkerManager implements WorkerLookupStore {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);

    private final WorkerStorage workerStorage;
    private final WorkerReachabilityView reachabilityView;
    private final WorkerLoadView workerLoadView;
    private final Object workerRegistryLock = new Object();
    private final LinkedHashMap<String, Worker> workerRegistryRows = new LinkedHashMap<>();
    private volatile WorkerRegistrySnapshot workerRegistrySnapshot;

    public WorkerManager(WorkerStorage workerStorage) {
        this(workerStorage, WorkerReachabilityView.permissive(), new InMemoryWorkerLoadView());
    }

    public WorkerManager(WorkerStorage workerStorage, WorkerReachabilityView reachabilityView) {
        this(workerStorage, reachabilityView, new InMemoryWorkerLoadView());
    }

    public WorkerManager(WorkerStorage workerStorage,
                         WorkerReachabilityView reachabilityView,
                         WorkerLoadView workerLoadView) {
        this.workerStorage = workerStorage;
        this.reachabilityView = reachabilityView != null ? reachabilityView : WorkerReachabilityView.permissive();
        this.workerLoadView = workerLoadView != null ? workerLoadView : new InMemoryWorkerLoadView();
        for (Worker worker : workerStorage.getAllWorkers()) {
            putRegistryRow(worker);
        }
        this.workerRegistrySnapshot = WorkerGroupCompatibilityProjection.snapshotFromWorkers(workerRegistryRows.values());
    }

    public void addWorker(Worker worker) {
        workerStorage.addWorker(worker);
        syncWorkerCapacity(worker);
        synchronized (workerRegistryLock) {
            putRegistryRow(worker);
            publishWorkerRegistrySnapshot();
        }
    }

    public Worker getWorker(String workerId) {
        return workerStorage.getWorker(workerId).orElse(null);
    }

    @Override
    public Worker findWorker(String workerId) {
        return getWorker(workerId);
    }

    public boolean updateWorker(Worker worker) {
        boolean updated = workerStorage.updateWorker(worker);
        if (updated) {
            syncWorkerCapacity(worker);
            synchronized (workerRegistryLock) {
                putRegistryRow(worker);
                publishWorkerRegistrySnapshot();
            }
        }
        return updated;
    }

    public boolean deleteWorker(String workerId) {
        boolean deleted = workerStorage.deleteWorker(workerId);
        if (deleted) {
            synchronized (workerRegistryLock) {
                workerRegistryRows.remove(normalizeNullable(workerId));
                publishWorkerRegistrySnapshot();
            }
        }
        return deleted;
    }

    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workerStorage.getWorkersByGroupId(workerGroupId);
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
            return getWorkerCandidateIndex().workersFor(task);
        }
        if (eventCode != null && !eventCode.isBlank()) {
            return getWorkerCandidateIndex().workersFor(task);
        }
        if (project != null && !project.isBlank()) {
            return getWorkerCandidateIndex().workersFor(task);
        }
        return workerStorage.getAllWorkers();
    }

    public WorkerRegistrySnapshot getWorkerRegistrySnapshot() {
        return workerRegistrySnapshot;
    }

    public WorkerCandidateIndex getWorkerCandidateIndex() {
        return new WorkerCandidateIndex(workerRegistrySnapshot);
    }

    public void refreshWorkerRegistrySnapshot() {
        synchronized (workerRegistryLock) {
            workerRegistryRows.clear();
            for (Worker worker : workerStorage.getAllWorkers()) {
                putRegistryRow(worker);
            }
            publishWorkerRegistrySnapshot();
        }
    }

    public List<String> getLockedWorkers() {
        return workerStorage.getLockedWorkers();
    }

    /**
     * Updates the engine-owned worker model status only.
     *
     * <p>This helper does not own transport reachability truth. Dispatch
     * eligibility must still read {@link #getWorkerReachability(String)}.</p>
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

    /**
     * Legacy worker-model availability helper.
     *
     * <p>This reflects {@link WorkerStatus} on the engine-owned worker record,
     * not transport presence. SDK-facing online queries should prefer the
     * transport-owned presence view when available.</p>
     */
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

    public WorkerLoadSnapshot getWorkerLoad(String workerId) {
        syncWorkerCapacity(workerId);
        return workerLoadView.snapshot(workerId);
    }

    public int getActiveWorkerCountForTask(String taskId) {
        return workerLoadView.getActiveWorkerCountForTask(taskId);
    }

    public boolean tryReserveWorkerCapacity(String workerId, String taskId) {
        syncWorkerCapacity(workerId);
        return workerLoadView.tryReserveCapacity(workerId, taskId);
    }

    public boolean confirmWorkerReservation(String workerId, String taskId) {
        return workerLoadView.confirmReservation(workerId, taskId);
    }

    public void releaseWorkerReservation(String workerId, String taskId) {
        workerLoadView.releaseReservation(workerId, taskId);
    }

    public void recordWorkClaimed(String workerId, String taskId) {
        workerLoadView.recordWorkClaimed(workerId, taskId);
    }

    public void recordWorkFinal(String workerId, String taskId) {
        workerLoadView.recordWorkFinal(workerId, taskId);
    }

    private void syncWorkerCapacity(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        syncWorkerCapacity(getWorker(workerId));
    }

    private void syncWorkerCapacity(Worker worker) {
        if (worker == null) {
            return;
        }
        workerLoadView.recordDeclaredCapacity(worker.getWorkerId(), worker.getMaxConcurrentWork());
    }

    private void putRegistryRow(Worker worker) {
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        if (workerId != null) {
            workerRegistryRows.put(workerId, worker);
        }
    }

    private void publishWorkerRegistrySnapshot() {
        this.workerRegistrySnapshot = WorkerGroupCompatibilityProjection.snapshotFromWorkers(workerRegistryRows.values());
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
