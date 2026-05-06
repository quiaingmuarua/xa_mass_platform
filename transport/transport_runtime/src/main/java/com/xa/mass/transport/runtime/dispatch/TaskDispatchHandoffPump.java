package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchHandoff;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains a dispatch handoff queue and forwards batches into the transport
 * routing listener.
 */
public final class TaskDispatchHandoffPump {

    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final TaskDispatchHandoff handoff;
    private final TaskDispatchBatchListener listener;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TaskDispatchHandoffPump(TaskDispatchHandoff handoff,
                                   TaskDispatchBatchListener listener,
                                   RuntimeTaskExecutor executor) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        drainLoop = executor.submit(this::drainLoop);
    }

    public void stop() {
        running = false;
        handoff.shutdown();
        Future<?> currentDrainLoop = drainLoop;
        if (currentDrainLoop != null) {
            currentDrainLoop.cancel(true);
            drainLoop = null;
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                TaskDispatchBatch batch = handoff.poll(POLL_TIMEOUT_MILLIS);
                if (batch == null) {
                    continue;
                }
                listener.onTaskDispatchBatch(batch.task(), batch.dispatchBindings());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
