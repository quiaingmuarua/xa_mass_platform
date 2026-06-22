package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumer;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded Java host unit for one adapter contribution.
 */
public final class EmbeddedAdapterContributionHost {

    private final List<TransportBinding> bindings;
    private final List<AdapterMailboxConsumer> adapterMailboxConsumers;
    private final List<ManagedTransportAdapter> managedTransportAdapters;
    private final List<TransportServer> transportServers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmbeddedAdapterContributionHost(TransportAdapterContribution contribution) {
        TransportAdapterContribution resolved = contribution != null
                ? contribution
                : TransportAdapterContribution.empty();
        this.bindings = List.copyOf(resolved.getTransportBindings());
        this.adapterMailboxConsumers = List.copyOf(resolved.getAdapterMailboxConsumers());
        this.managedTransportAdapters = List.copyOf(resolved.getManagedTransportAdapters());
        this.transportServers = List.copyOf(resolved.getTransportServers());
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
            for (AdapterMailboxConsumer adapterMailboxConsumer : adapterMailboxConsumers) {
                adapterMailboxConsumer.start();
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
        failure = stopAdapterMailboxConsumers(failure);
        failure = stopServers(failure);
        failure = stopManagedAdapters(failure);
        if (failure != null) {
            throw failure;
        }
    }

    public boolean isRunning() {
        return running.get()
                && managedTransportAdapters.stream().allMatch(ManagedTransportAdapter::isRunning)
                && transportServers.stream().allMatch(TransportServer::isRunning)
                && adapterMailboxConsumers.stream().allMatch(AdapterMailboxConsumer::isRunning);
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

    private RuntimeException stopAdapterMailboxConsumers(RuntimeException failure) {
        for (AdapterMailboxConsumer adapterMailboxConsumer : adapterMailboxConsumers) {
            try {
                adapterMailboxConsumer.stop();
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
