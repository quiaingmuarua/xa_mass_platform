package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TransportResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains a Redis result inbox into the engine-local result ingest channel.
 */
public final class TaskResultIngestInboxPump {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultIngestInboxPump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final RedisTaskResultIngestChannel inbox;
    private final TaskResultIngestChannel delegate;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TaskResultIngestInboxPump(RedisTaskResultIngestChannel inbox,
                                     TaskResultIngestChannel delegate,
                                     RuntimeTaskExecutor executor) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
        Future<?> currentDrainLoop = drainLoop;
        if (currentDrainLoop != null) {
            currentDrainLoop.cancel(true);
            drainLoop = null;
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                TransportResultEnvelope envelope = inbox.pollEnvelope(POLL_TIMEOUT_MILLIS);
                if (envelope == null) {
                    continue;
                }
                delegate.ingest(envelope);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Task result inbox item failed; continuing drain loop", e);
            }
        }
    }
}
