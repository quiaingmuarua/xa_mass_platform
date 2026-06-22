package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerAvailability;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes narrow queue-consumer availability for one adapter mailbox.
 */
public final class MailboxConsumerAvailabilityPublisher {

    private static final Logger logger = LoggerFactory.getLogger(MailboxConsumerAvailabilityPublisher.class);
    private static final long MIN_REFRESH_INTERVAL_MILLIS = 100L;

    private final String adapterMailboxKey;
    private final String consumerId;
    private final AdapterMailboxConsumerRegistry registry;
    private final long availabilityMillis;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AdapterMailboxConsumerAvailability currentAvailability;
    private volatile Future<?> refreshTask;

    public MailboxConsumerAvailabilityPublisher(String adapterMailboxKey,
                                                String consumerId,
                                                AdapterMailboxConsumerRegistry registry,
                                                long availabilityMillis,
                                                RuntimeTaskExecutor runtimeTaskExecutor) {
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        this.consumerId = requireText(consumerId, "consumerId");
        this.registry = registry != null ? registry : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        if (availabilityMillis <= 0L) {
            throw new IllegalArgumentException("availabilityMillis must be greater than 0");
        }
        this.availabilityMillis = availabilityMillis;
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
    }

    public String adapterMailboxKey() {
        return adapterMailboxKey;
    }

    public String consumerId() {
        return consumerId;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        publishNextAvailability();
        if (registry != NoopAdapterMailboxConsumerRegistry.INSTANCE) {
            refreshTask = runtimeTaskExecutor.submit(this::runRefreshLoop);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Future<?> task = refreshTask;
        refreshTask = null;
        if (task != null) {
            task.cancel(true);
        }
        AdapterMailboxConsumerAvailability availability = currentAvailability;
        currentAvailability = null;
        if (availability != null && registry != NoopAdapterMailboxConsumerRegistry.INSTANCE) {
            try {
                registry.removeMailboxConsumerAvailability(availability);
            } catch (RuntimeException e) {
                logger.warn("Failed to release adapter mailbox consumer availability: adapterMailboxKey={}, reason={}",
                        availability.adapterMailboxKey(), e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runRefreshLoop() {
        long refreshIntervalMillis = Math.max(
                MIN_REFRESH_INTERVAL_MILLIS,
                Math.max(1L, availabilityMillis / 3L)
        );
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                TimeUnit.MILLISECONDS.sleep(refreshIntervalMillis);
                publishNextAvailability();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logger.warn("Failed to refresh adapter mailbox consumer availability: adapterMailboxKey={}, reason={}",
                        adapterMailboxKey, e.getMessage());
            }
        }
    }

    private void publishNextAvailability() {
        if (registry == NoopAdapterMailboxConsumerRegistry.INSTANCE) {
            return;
        }
        AdapterMailboxConsumerAvailability previous = currentAvailability;
        AdapterMailboxConsumerAvailability next = new AdapterMailboxConsumerAvailability(
                adapterMailboxKey,
                consumerId,
                previous == null ? 1L : previous.generation(),
                System.currentTimeMillis() + availabilityMillis
        );
        registry.publishMailboxConsumerAvailability(next);
        currentAvailability = next;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
