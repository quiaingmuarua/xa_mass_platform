package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.MailboxConsumerAvailabilityPublisher;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared embedded helper for one adapter-owned mailbox consumer.
 */
public final class AdapterMailboxConsumerLoop implements AdapterMailboxConsumer {

    public static final int DEFAULT_MAX_ITEMS = 64;
    public static final long DEFAULT_POLL_TIMEOUT_MILLIS = 250L;

    private static final Logger logger = LoggerFactory.getLogger(AdapterMailboxConsumerLoop.class);

    private final String adapterMailboxKey;
    private final AdapterMailboxClient mailboxClient;
    private final AdapterCommandExecutor commandExecutor;
    private final DeliveryFailureEvidenceSink failureEvidenceSink;
    private final MailboxConsumerAvailabilityPublisher availabilityPublisher;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final int maxItems;
    private final long pollTimeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Future<?> drainLoop;

    public AdapterMailboxConsumerLoop(String adapterMailboxKey,
                                      AdapterMailboxClient mailboxClient,
                                      AdapterCommandExecutor commandExecutor,
                                      DeliveryFailureEvidenceSink failureEvidenceSink,
                                      MailboxConsumerAvailabilityPublisher availabilityPublisher,
                                      RuntimeTaskExecutor runtimeTaskExecutor) {
        this(
                adapterMailboxKey,
                mailboxClient,
                commandExecutor,
                failureEvidenceSink,
                availabilityPublisher,
                runtimeTaskExecutor,
                DEFAULT_MAX_ITEMS,
                DEFAULT_POLL_TIMEOUT_MILLIS
        );
    }

    public AdapterMailboxConsumerLoop(String adapterMailboxKey,
                                      AdapterMailboxClient mailboxClient,
                                      AdapterCommandExecutor commandExecutor,
                                      DeliveryFailureEvidenceSink failureEvidenceSink,
                                      MailboxConsumerAvailabilityPublisher availabilityPublisher,
                                      RuntimeTaskExecutor runtimeTaskExecutor,
                                      int maxItems,
                                      long pollTimeoutMillis) {
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        this.mailboxClient = Objects.requireNonNull(mailboxClient, "mailboxClient");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.failureEvidenceSink = failureEvidenceSink != null ? failureEvidenceSink : ignored -> { };
        this.availabilityPublisher = availabilityPublisher;
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be greater than 0");
        }
        if (pollTimeoutMillis < 0L) {
            throw new IllegalArgumentException("pollTimeoutMillis must be non-negative");
        }
        this.maxItems = maxItems;
        this.pollTimeoutMillis = pollTimeoutMillis;
    }

    @Override
    public String adapterMailboxKey() {
        return adapterMailboxKey;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (availabilityPublisher != null) {
            availabilityPublisher.start();
        }
        drainLoop = runtimeTaskExecutor.submit(this::drainLoop);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Future<?> current = drainLoop;
        drainLoop = null;
        if (current != null) {
            current.cancel(true);
        }
        if (availabilityPublisher != null) {
            availabilityPublisher.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void drainLoop() {
        while (running.get()) {
            try {
                List<DispatchMessage> items = mailboxClient.poll(
                        adapterMailboxKey,
                        maxItems,
                        pollTimeoutMillis
                );
                if (items == null || items.isEmpty()) {
                    continue;
                }
                List<DispatchOutcome> outcomes = dispatch(items);
                emitFailureEvidence(outcomes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Adapter-owned mailbox consumer failed; continuing: adapterMailboxKey={}",
                        adapterMailboxKey, e);
            }
        }
    }

    private List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        try {
            List<DispatchOutcome> outcomes = commandExecutor.dispatch(List.copyOf(items));
            return outcomes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(outcomes));
        } catch (RuntimeException e) {
            logger.error("Adapter-owned final-hop dispatch failed: adapterMailboxKey={}, items={}",
                    adapterMailboxKey, items.size(), e);
            return items.stream()
                    .map(item -> DispatchOutcomeFactory.unavailable(item, e.getMessage()))
                    .toList();
        }
    }

    private void emitFailureEvidence(List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        try {
            failureEvidenceSink.accept(outcomes);
        } catch (RuntimeException e) {
            logger.error("Delivery failure evidence sink failed after destructive poll; engine timeout remains recovery path: adapterMailboxKey={}",
                    adapterMailboxKey, e);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
