package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded adapter host collection owned by starter assembly.
 */
public final class EmbeddedAdapterHostSet {

    private static final EmbeddedAdapterHostSet EMPTY = new EmbeddedAdapterHostSet(List.of());

    private final List<EmbeddedAdapterContributionHost> hosts;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private EmbeddedAdapterHostSet(List<EmbeddedAdapterContributionHost> hosts) {
        this.hosts = List.copyOf(hosts);
    }

    public static EmbeddedAdapterHostSet empty() {
        return EMPTY;
    }

    public static EmbeddedAdapterHostSet fromContributions(
            List<TransportAdapterContribution> contributions,
            TransportDispatchHandoff handoff,
            TransportDeliveryFailureHandler failureHandler,
            AdapterMailboxConsumerRegistry mailboxConsumerRegistry,
            long mailboxConsumerAvailabilityMillis,
            RuntimeTaskExecutor runtimeTaskExecutor) {
        if (contributions == null || contributions.isEmpty()) {
            return empty();
        }
        AdapterMailboxConsumerRegistry registry = mailboxConsumerRegistry != null
                ? mailboxConsumerRegistry
                : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        List<EmbeddedAdapterContributionHost> hosts = new ArrayList<>();
        for (TransportAdapterContribution contribution : contributions) {
            hosts.add(new EmbeddedAdapterContributionHost(
                    contribution,
                    handoff,
                    failureHandler,
                    registry,
                    mailboxConsumerAvailabilityMillis,
                    runtimeTaskExecutor
            ));
        }
        return new EmbeddedAdapterHostSet(hosts);
    }

    public List<TransportBinding> bindings() {
        List<TransportBinding> bindings = new ArrayList<>();
        for (EmbeddedAdapterContributionHost host : hosts) {
            bindings.addAll(host.bindings());
        }
        return List.copyOf(bindings);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (EmbeddedAdapterContributionHost host : hosts) {
                host.start();
            }
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        RuntimeException failure = null;
        for (EmbeddedAdapterContributionHost host : hosts) {
            try {
                host.stop();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public boolean isRunning() {
        return running.get() && hosts.stream().allMatch(EmbeddedAdapterContributionHost::isRunning);
    }
}
