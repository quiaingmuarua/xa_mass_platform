package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded Java runtime unit for one adapter contribution.
 */
public final class EmbeddedAdapterContributionRuntime {

    private final List<TransportBinding> bindings;
    private final List<ManagedTransportAdapter> managedTransportAdapters;
    private final List<TransportServer> transportServers;
    private final List<AdapterMailboxLeaseRuntime> mailboxLeaseRuntimes;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmbeddedAdapterContributionRuntime(TransportAdapterContribution contribution,
                                              AdapterMailboxConsumerRegistry mailboxConsumerRegistry,
                                              long mailboxConsumerLeaseMillis,
                                              RuntimeTaskExecutor runtimeTaskExecutor) {
        TransportAdapterContribution resolved = contribution != null
                ? contribution
                : TransportAdapterContribution.empty();
        this.bindings = List.copyOf(resolved.getTransportBindings());
        this.managedTransportAdapters = List.copyOf(resolved.getManagedTransportAdapters());
        this.transportServers = List.copyOf(resolved.getTransportServers());
        AdapterMailboxConsumerRegistry registry = mailboxConsumerRegistry != null
                ? mailboxConsumerRegistry
                : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        List<AdapterMailboxLeaseRuntime> leaseRuntimes = new ArrayList<>();
        for (TransportBinding binding : bindings) {
            leaseRuntimes.add(new AdapterMailboxLeaseRuntime(
                    binding,
                    registry,
                    mailboxConsumerLeaseMillis,
                    Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor")
            ));
        }
        this.mailboxLeaseRuntimes = List.copyOf(leaseRuntimes);
    }

    public List<TransportBinding> bindings() {
        return bindings;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (ManagedTransportAdapter managedTransportAdapter : managedTransportAdapters) {
                managedTransportAdapter.start();
            }
            for (TransportServer transportServer : transportServers) {
                try {
                    transportServer.start();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to start transport server", e);
                }
            }
            for (AdapterMailboxLeaseRuntime mailboxLeaseRuntime : mailboxLeaseRuntimes) {
                mailboxLeaseRuntime.start();
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
        failure = stopServers(failure);
        failure = stopManagedAdapters(failure);
        failure = stopMailboxLeases(failure);
        if (failure != null) {
            throw failure;
        }
    }

    public boolean isRunning() {
        return running.get()
                && managedTransportAdapters.stream().allMatch(ManagedTransportAdapter::isRunning)
                && transportServers.stream().allMatch(TransportServer::isRunning)
                && mailboxLeaseRuntimes.stream().allMatch(AdapterMailboxLeaseRuntime::isRunning);
    }

    private RuntimeException stopServers(RuntimeException failure) {
        for (TransportServer transportServer : transportServers) {
            try {
                transportServer.stop();
            } catch (RuntimeException e) {
                failure = append(failure, e);
            } catch (Exception e) {
                failure = append(failure, new RuntimeException("Failed to stop transport server", e));
            }
        }
        return failure;
    }

    private RuntimeException stopManagedAdapters(RuntimeException failure) {
        for (ManagedTransportAdapter managedTransportAdapter : managedTransportAdapters) {
            try {
                managedTransportAdapter.stop();
            } catch (RuntimeException e) {
                failure = append(failure, e);
            }
        }
        return failure;
    }

    private RuntimeException stopMailboxLeases(RuntimeException failure) {
        for (AdapterMailboxLeaseRuntime mailboxLeaseRuntime : mailboxLeaseRuntimes) {
            try {
                mailboxLeaseRuntime.stop();
            } catch (RuntimeException e) {
                failure = append(failure, e);
            }
        }
        return failure;
    }

    private static RuntimeException append(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }
}
