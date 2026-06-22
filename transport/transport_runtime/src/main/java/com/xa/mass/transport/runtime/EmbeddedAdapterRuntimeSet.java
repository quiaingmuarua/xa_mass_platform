package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded adapter runtime collection owned by starter assembly.
 */
public final class EmbeddedAdapterRuntimeSet {

    private static final EmbeddedAdapterRuntimeSet EMPTY = new EmbeddedAdapterRuntimeSet(List.of());

    private final List<EmbeddedAdapterContributionRuntime> runtimes;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private EmbeddedAdapterRuntimeSet(List<EmbeddedAdapterContributionRuntime> runtimes) {
        this.runtimes = List.copyOf(runtimes);
    }

    public static EmbeddedAdapterRuntimeSet empty() {
        return EMPTY;
    }

    public static EmbeddedAdapterRuntimeSet fromContributions(
            List<TransportAdapterContribution> contributions,
            AdapterMailboxConsumerRegistry mailboxConsumerRegistry,
            long mailboxConsumerLeaseMillis,
            RuntimeTaskExecutor runtimeTaskExecutor) {
        if (contributions == null || contributions.isEmpty()) {
            return empty();
        }
        AdapterMailboxConsumerRegistry registry = mailboxConsumerRegistry != null
                ? mailboxConsumerRegistry
                : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        List<EmbeddedAdapterContributionRuntime> runtimes = new ArrayList<>();
        for (TransportAdapterContribution contribution : contributions) {
            runtimes.add(new EmbeddedAdapterContributionRuntime(
                    contribution,
                    registry,
                    mailboxConsumerLeaseMillis,
                    runtimeTaskExecutor
            ));
        }
        return new EmbeddedAdapterRuntimeSet(runtimes);
    }

    public List<TransportBinding> bindings() {
        List<TransportBinding> bindings = new ArrayList<>();
        for (EmbeddedAdapterContributionRuntime runtime : runtimes) {
            bindings.addAll(runtime.bindings());
        }
        return List.copyOf(bindings);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (EmbeddedAdapterContributionRuntime runtime : runtimes) {
                runtime.start();
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
        for (EmbeddedAdapterContributionRuntime runtime : runtimes) {
            try {
                runtime.stop();
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
        return !running.get() || runtimes.stream().allMatch(EmbeddedAdapterContributionRuntime::isRunning);
    }
}
