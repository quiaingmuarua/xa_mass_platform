package com.xa.mass.engine.worker;

import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * Legacy observer for runtime worker system events.
 *
 * <p>Reachability truth lives in transport presence rather than the engine
 * worker model; this listener only refreshes model heartbeat evidence for
 * existing worker rows.</p>
 */
public final class WorkerStatusEventListener {
    private static final Logger log = LoggerFactory.getLogger(WorkerStatusEventListener.class);

    private final WorkerManager workerManager;
    private final Runnable dispatchWakeupCallback;

    public WorkerStatusEventListener(WorkerManager workerManager) {
        this(workerManager, null);
    }

    public WorkerStatusEventListener(WorkerManager workerManager, Runnable dispatchWakeupCallback) {
        this.workerManager = workerManager;
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    @MassSubscribe
    public void onWorkerOnline(WorkerOnlineEvent event) {
        if (recordHeartbeat(event.getWorkerId())) {
            notifyDispatchWakeup();
        }
    }

    @MassSubscribe
    public void onWorkerHeartbeat(WorkerHeartbeatEvent event) {
        recordHeartbeat(event.getWorkerId());
    }

    @MassSubscribe
    public void onWorkerOffline(WorkerOfflineEvent event) {
        log.debug("Worker offline event observed for {}", event.getWorkerId());
    }

    private boolean recordHeartbeat(String workerId) {
        Worker worker = workerManager.getWorker(workerId);
        if (worker == null) {
            log.debug("Ignoring heartbeat for unregistered worker {}", workerId);
            return false;
        }
        worker.setLastHeartbeat(LocalDateTime.now());
        workerManager.updateWorker(worker);
        return true;
    }

    private void notifyDispatchWakeup() {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker online dispatch wakeup callback failed", e);
        }
    }
}
