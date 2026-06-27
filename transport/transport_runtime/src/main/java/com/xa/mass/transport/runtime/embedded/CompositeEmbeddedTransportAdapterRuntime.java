package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composite lifecycle for one embedded adapter runtime.
 */
public final class CompositeEmbeddedTransportAdapterRuntime implements EmbeddedTransportAdapterRuntime {

    private final TransportAdapterDescriptor descriptor;
    private final TransportBinding binding;
    private final List<ManagedTransportAdapter> managedAdapters;
    private final List<TransportServer> servers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CompositeEmbeddedTransportAdapterRuntime(TransportAdapterDescriptor descriptor,
                                                    TransportBinding binding,
                                                    List<ManagedTransportAdapter> managedAdapters,
                                                    List<TransportServer> servers) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.managedAdapters = managedAdapters == null ? List.of() : List.copyOf(managedAdapters);
        this.servers = servers == null ? List.of() : List.copyOf(servers);
    }

    @Override
    public TransportAdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public TransportBinding binding() {
        return binding;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        List<Object> started = new ArrayList<>();
        try {
            for (ManagedTransportAdapter adapter : managedAdapters) {
                started.add(adapter);
                adapter.start();
            }
            for (TransportServer server : servers) {
                started.add(server);
                server.start();
            }
        } catch (Exception e) {
            stopStarted(started);
            running.set(false);
            throw new IllegalStateException("Failed to start embedded adapter runtime: "
                    + descriptor.getAdapterId(), e);
        }
    }

    @Override
    public boolean isRunning() {
        if (!running.get()) {
            return false;
        }
        for (ManagedTransportAdapter adapter : managedAdapters) {
            if (!adapter.isRunning()) {
                return false;
            }
        }
        for (TransportServer server : servers) {
            if (!server.isRunning()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (int i = servers.size() - 1; i >= 0; i--) {
            stopQuietly(servers.get(i));
        }
        for (int i = managedAdapters.size() - 1; i >= 0; i--) {
            stopQuietly(managedAdapters.get(i));
        }
    }

    private void stopStarted(List<Object> started) {
        for (int i = started.size() - 1; i >= 0; i--) {
            stopQuietly(started.get(i));
        }
    }

    private void stopQuietly(Object resource) {
        try {
            if (resource instanceof TransportServer server) {
                server.stop();
            } else if (resource instanceof ManagedTransportAdapter adapter) {
                adapter.stop();
            }
        } catch (Exception ignored) {
            // Best-effort startup/close cleanup.
        }
    }
}
