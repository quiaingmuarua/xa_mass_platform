package com.xa.mass.engine;

import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.watchdog.RuntimeReadyDispatchPump;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine-owned worker-availability wakeup fanout.
 *
 * <p>Worker and transport owners call this bridge through a narrow callback.
 * They must not directly scan tasks, call the runtime-ready pump, or publish
 * task dispatch requests without an engine-owned task source.</p>
 */
public final class TaskDispatchWakeupBridge {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchWakeupBridge.class);

    private final TaskAssignWorker assignWorker;
    private final Runnable runtimeReadyWakeup;

    public TaskDispatchWakeupBridge(TaskAssignWorker assignWorker,
                                    RuntimeReadyDispatchPump runtimeReadyDispatchPump) {
        this(assignWorker,
                runtimeReadyDispatchPump != null ? runtimeReadyDispatchPump::wakeIdleAdmissions : null);
    }

    public TaskDispatchWakeupBridge(TaskAssignWorker assignWorker,
                                    Runnable runtimeReadyWakeup) {
        this.assignWorker = assignWorker;
        this.runtimeReadyWakeup = runtimeReadyWakeup != null ? runtimeReadyWakeup : () -> {
        };
    }

    public void wake(String reason) {
        int laneRetriesWoken = 0;
        if (assignWorker != null) {
            try {
                laneRetriesWoken = assignWorker.wakeWaitingRetries(reason);
            } catch (RuntimeException e) {
                log.warn("Task assignment retry wakeup failed: {}", reason, e);
            }
        }
        try {
            runtimeReadyWakeup.run();
        } catch (RuntimeException e) {
            log.warn("Runtime-ready dispatch wakeup failed: {}", reason, e);
        }
        if (laneRetriesWoken > 0) {
            log.debug("Worker availability wakeup accelerated {} assignment retry task(s): {}",
                    laneRetriesWoken, reason);
        }
    }

    public Runnable callback(String reason) {
        return () -> wake(reason);
    }
}
