package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerLease;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded-runtime owner for one adapter mailbox consumer lease.
 */
public final class AdapterMailboxLeaseRuntime {

    private static final Logger logger = LoggerFactory.getLogger(AdapterMailboxLeaseRuntime.class);
    private static final long MIN_REFRESH_INTERVAL_MILLIS = 100L;

    private final TransportBinding binding;
    private final AdapterMailboxConsumerRegistry registry;
    private final long leaseMillis;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AdapterMailboxConsumerLease currentLease;
    private volatile Future<?> refreshTask;

    public AdapterMailboxLeaseRuntime(TransportBinding binding,
                                      AdapterMailboxConsumerRegistry registry,
                                      long leaseMillis,
                                      RuntimeTaskExecutor runtimeTaskExecutor) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.registry = registry != null ? registry : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.leaseMillis = leaseMillis;
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
    }

    public TransportBinding binding() {
        return binding;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        claimNextLease();
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
        AdapterMailboxConsumerLease lease = currentLease;
        currentLease = null;
        if (lease != null && registry != NoopAdapterMailboxConsumerRegistry.INSTANCE) {
            try {
                registry.releaseMailboxConsumer(lease);
            } catch (RuntimeException e) {
                logger.warn("Failed to release adapter mailbox consumer lease: adapterMailboxKey={}, reason={}",
                        lease.adapterMailboxKey(), e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runRefreshLoop() {
        long refreshIntervalMillis = Math.max(
                MIN_REFRESH_INTERVAL_MILLIS,
                Math.max(1L, leaseMillis / 3L)
        );
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                TimeUnit.MILLISECONDS.sleep(refreshIntervalMillis);
                claimNextLease();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logger.warn("Failed to refresh adapter mailbox consumer lease: adapterMailboxKey={}, reason={}",
                        binding.getAdapterMailboxKey(), e.getMessage());
            }
        }
    }

    private void claimNextLease() {
        if (registry == NoopAdapterMailboxConsumerRegistry.INSTANCE) {
            return;
        }
        AdapterMailboxConsumerLease previous = currentLease;
        AdapterMailboxConsumerLease next = new AdapterMailboxConsumerLease(
                binding.getAdapterMailboxKey(),
                consumerId(binding),
                previous == null ? 1L : previous.generation(),
                System.currentTimeMillis() + leaseMillis
        );
        registry.claimMailboxConsumer(next);
        currentLease = next;
    }

    private static String consumerId(TransportBinding binding) {
        return "embedded:" + binding.getAdapterMailboxKey();
    }
}
