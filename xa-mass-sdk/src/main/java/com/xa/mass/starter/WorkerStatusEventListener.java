package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Legacy observer for process-local runtime worker system events.
 *
 * <p>Reachability truth lives in transport presence. This bridge only refreshes
 * model heartbeat evidence for existing worker rows.</p>
 */
public final class WorkerStatusEventListener {
    private static final Logger log = LoggerFactory.getLogger(WorkerStatusEventListener.class);

    private final WorkerResourceRuntime workerResourceRuntime;
    private final Runnable dispatchWakeupCallback;

    WorkerStatusEventListener(WorkerResourceRuntime workerResourceRuntime, Runnable dispatchWakeupCallback) {
        this.workerResourceRuntime = Objects.requireNonNull(workerResourceRuntime, "workerResourceRuntime");
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
        WorkerResourceRecord worker = workerResourceRuntime.worker(workerId).orElse(null);
        if (worker == null) {
            log.debug("Ignoring heartbeat for unregistered worker {}", workerId);
            return false;
        }
        workerResourceRuntime.updateWorker(withHeartbeat(worker, LocalDateTime.now()));
        return true;
    }

    private static WorkerResourceRecord withHeartbeat(WorkerResourceRecord worker, LocalDateTime lastHeartbeat) {
        return new WorkerResourceRecord(
                worker.workerId(),
                worker.statusName(),
                worker.agentVersion(),
                lastHeartbeat,
                worker.supportedProjects(),
                worker.supportedEventCodes(),
                worker.workerGroupId(),
                worker.adapterNodeId(),
                worker.adapterId(),
                worker.onlineStrategy(),
                worker.maxConcurrentWork(),
                worker.attributes(),
                worker.createTime(),
                worker.updateTime()
        );
    }

    private void notifyDispatchWakeup() {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker online dispatch wakeup callback failed", e);
        }
    }
}
