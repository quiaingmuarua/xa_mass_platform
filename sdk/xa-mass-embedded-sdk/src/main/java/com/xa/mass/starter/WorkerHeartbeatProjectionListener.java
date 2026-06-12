package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Process-local projection from worker lifecycle events into worker runtime
 * state.
 *
 * <p>The events are worker-runtime lifecycle evidence. Transport route-owner
 * leases remain delivery feasibility evidence and must not drive this
 * projection.</p>
 */
public final class WorkerHeartbeatProjectionListener {
    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeatProjectionListener.class);

    private final WorkerResourceRuntime workerResourceRuntime;
    private final Runnable dispatchWakeupCallback;

    WorkerHeartbeatProjectionListener(WorkerResourceRuntime workerResourceRuntime, Runnable dispatchWakeupCallback) {
        this.workerResourceRuntime = Objects.requireNonNull(workerResourceRuntime, "workerResourceRuntime");
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    @MassSubscribe
    public void onWorkerOnline(WorkerOnlineEvent event) {
        if (projectWorkerState(event.getWorkerId(), WorkerStatus.ONLINE, true).updated()) {
            notifyDispatchWakeup();
        }
    }

    @MassSubscribe
    public void onWorkerHeartbeat(WorkerHeartbeatEvent event) {
        ProjectionUpdate update = projectWorkerState(event.getWorkerId(), WorkerStatus.ONLINE, true);
        if (update.becameAvailable()) {
            notifyDispatchWakeup();
        }
    }

    @MassSubscribe
    public void onWorkerOffline(WorkerOfflineEvent event) {
        projectWorkerState(event.getWorkerId(), WorkerStatus.OFFLINE, false);
    }

    private ProjectionUpdate projectWorkerState(String workerId, WorkerStatus status, boolean refreshHeartbeat) {
        WorkerResourceRecord worker = workerResourceRuntime.worker(workerId).orElse(null);
        if (worker == null) {
            log.debug("Ignoring worker lifecycle event for unregistered worker {}", workerId);
            return ProjectionUpdate.unchanged();
        }
        boolean becameAvailable = !workerStatusAvailable(worker.statusName()) && status.isAvailable();
        WorkerResourceRecord updated = withRuntimeState(
                worker,
                status,
                refreshHeartbeat ? LocalDateTime.now() : worker.lastHeartbeat()
        );
        boolean persisted = workerResourceRuntime.updateWorker(updated);
        return persisted ? new ProjectionUpdate(true, becameAvailable) : ProjectionUpdate.unchanged();
    }

    private static WorkerResourceRecord withRuntimeState(WorkerResourceRecord worker,
                                                         WorkerStatus status,
                                                         LocalDateTime lastHeartbeat) {
        return new WorkerResourceRecord(
                worker.workerId(),
                status.name(),
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
                LocalDateTime.now()
        );
    }

    private static boolean workerStatusAvailable(String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return false;
        }
        try {
            return WorkerStatus.valueOf(statusName.trim()).isAvailable();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void notifyDispatchWakeup() {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker online dispatch wakeup callback failed", e);
        }
    }

    private record ProjectionUpdate(boolean updated, boolean becameAvailable) {
        private static ProjectionUpdate unchanged() {
            return new ProjectionUpdate(false, false);
        }
    }
}
