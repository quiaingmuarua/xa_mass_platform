package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandBatch;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded host mount that drains one adapter mailbox and invokes its local
 * final-hop command executor.
 */
public final class AdapterMailboxMount {

    private static final Logger logger = LoggerFactory.getLogger(AdapterMailboxMount.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final TransportBinding binding;
    private final TransportDeliveryCommandHandoff handoff;
    private final MailboxConsumerAvailabilityPublisher availabilityPublisher;
    private final TransportDeliveryFailureHandler failureHandler;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Future<?> drainLoop;

    public AdapterMailboxMount(TransportBinding binding,
                               TransportDeliveryCommandHandoff handoff,
                               MailboxConsumerAvailabilityPublisher availabilityPublisher,
                               TransportDeliveryFailureHandler failureHandler,
                               RuntimeTaskExecutor runtimeTaskExecutor) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.availabilityPublisher = availabilityPublisher;
        this.failureHandler = failureHandler;
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
    }

    public TransportBinding binding() {
        return binding;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        drainLoop = runtimeTaskExecutor.submit(this::drainLoop);
        if (availabilityPublisher != null) {
            availabilityPublisher.start();
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Future<?> currentDrainLoop = drainLoop;
        drainLoop = null;
        if (currentDrainLoop != null) {
            currentDrainLoop.cancel(true);
        }
        if (availabilityPublisher != null) {
            availabilityPublisher.stop();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void drainLoop() {
        String adapterMailboxKey = binding.getAdapterMailboxKey();
        while (running.get()) {
            try {
                DeliveryCommandBatch batch = handoff.poll(adapterMailboxKey, POLL_TIMEOUT_MILLIS);
                if (batch == null) {
                    continue;
                }
                if (!adapterMailboxKey.equals(batch.adapterMailboxKey())) {
                    logger.error("Handoff returned mismatched adapter mailbox batch: expected={}, actual={}",
                            adapterMailboxKey, batch.adapterMailboxKey());
                    continue;
                }
                handoff.complete(batch, dispatch(batch));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Adapter mailbox command drain failed; continuing: adapterMailboxKey={}",
                        adapterMailboxKey, e);
            }
        }
    }

    private List<DispatchOutcome> dispatch(DeliveryCommandBatch batch) {
        if (batch.items().isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes;
        try {
            outcomes = binding.getCommandExecutor().dispatch(batch.items());
        } catch (RuntimeException e) {
            logger.error("Delivery adapter mailbox dispatch failed: adapterId={}, adapterMailboxKey={}, requests={}",
                    binding.getAdapterId(), binding.getAdapterMailboxKey(), batch.items().size(), e);
            outcomes = unavailableOutcomes(batch.items(), e.getMessage());
        }
        List<DispatchOutcome> resolved = outcomes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(outcomes));
        handleRetryableFailures(resolved);
        logOutcomes(resolved);
        return resolved;
    }

    private List<DispatchOutcome> unavailableOutcomes(List<DeliveryCommand> commands, String reason) {
        List<DispatchOutcome> outcomes = new ArrayList<>(commands.size());
        for (DeliveryCommand command : commands) {
            outcomes.add(DispatchOutcome.unavailable(command, reason));
        }
        return Collections.unmodifiableList(outcomes);
    }

    private void handleRetryableFailures(List<DispatchOutcome> outcomes) {
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null || !outcome.isRetryable()) {
                continue;
            }
            if (failureHandler == null) {
                logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                        outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
                throw new DeliveryFailureEmissionException("delivery failure has no failure handler");
            }
            boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, outcome.getReason()));
            if (!handled) {
                logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                        outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
                throw new DeliveryFailureEmissionException("delivery failure was not handled");
            }
        }
    }

    private void logOutcomes(List<DispatchOutcome> outcomes) {
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (outcome.isRetryable()) {
                logger.warn("Transport delivery outcome: adapterId={}, adapterMailboxKey={}, deliveryId={}, selectedWorkerId={}, status={}, retryable={}, reason={}",
                        binding.getAdapterId(), binding.getAdapterMailboxKey(), outcome.getDeliveryId(),
                        outcome.getSelectedWorkerId(), outcome.getStatus(), true, outcome.getReason());
            } else {
                logger.debug("Transport delivery outcome: adapterId={}, adapterMailboxKey={}, deliveryId={}, selectedWorkerId={}, status={}",
                        binding.getAdapterId(), binding.getAdapterMailboxKey(), outcome.getDeliveryId(),
                        outcome.getSelectedWorkerId(), outcome.getStatus());
            }
        }
    }

    private static final class DeliveryFailureEmissionException extends RuntimeException {
        private DeliveryFailureEmissionException(String message) {
            super(message);
        }
    }
}
