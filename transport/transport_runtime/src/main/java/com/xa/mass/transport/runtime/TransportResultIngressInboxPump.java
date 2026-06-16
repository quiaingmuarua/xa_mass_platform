package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.TransportResultIngressHandler;
import com.xa.mass.transport.channel.TransportResultIngressOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains a Redis result inbox into the engine-local result ingress handler.
 */
public final class TransportResultIngressInboxPump {

    private static final Logger logger = LoggerFactory.getLogger(TransportResultIngressInboxPump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final RedisTransportResultIngressChannel inbox;
    private final TransportResultIngressHandler delegate;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TransportResultIngressInboxPump(RedisTransportResultIngressChannel inbox,
                                           TransportResultIngressHandler delegate,
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
                ClaimedTransportResultIngress claimed = inbox.poll(POLL_TIMEOUT_MILLIS);
                if (claimed == null) {
                    continue;
                }
                TransportResultIngressOutcome outcome = delegate.handle(claimed.envelope());
                if (outcome != null && outcome.ackable()) {
                    inbox.complete(claimed);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Task result inbox item failed; leaving claim retryable", e);
            }
        }
    }
}
