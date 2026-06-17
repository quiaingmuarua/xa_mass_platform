package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit output emitted by one adapter bootstrap.
 *
 * <p>Runtime inputs live in {@link TransportAdapterBootstrapContext}. This
 * contribution object owns adapter-produced runtime outputs so bootstrap
 * assembly cannot silently overwrite bindings, servers, raw side-channels, or
 * diagnostics in mutable context state.
 */
public final class TransportAdapterContribution {

    private static final TransportAdapterContribution EMPTY = builder().build();

    private final List<TransportBinding> transportBindings;
    private final List<ManagedTransportAdapter> managedTransportAdapters;
    private final List<TransportServer> transportServers;
    private final List<RawWorkerMessageChannel> rawWorkerMessageChannels;
    private final List<WorkerEndpointInspector> endpointInspectors;

    private TransportAdapterContribution(Builder builder) {
        this.transportBindings = List.copyOf(builder.transportBindings);
        this.managedTransportAdapters = List.copyOf(builder.managedTransportAdapters);
        this.transportServers = List.copyOf(builder.transportServers);
        this.rawWorkerMessageChannels = List.copyOf(builder.rawWorkerMessageChannels);
        this.endpointInspectors = List.copyOf(builder.endpointInspectors);
    }

    public static TransportAdapterContribution empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<TransportBinding> getTransportBindings() {
        return transportBindings;
    }

    public List<ManagedTransportAdapter> getManagedTransportAdapters() {
        return managedTransportAdapters;
    }

    public List<TransportServer> getTransportServers() {
        return transportServers;
    }

    public List<RawWorkerMessageChannel> getRawWorkerMessageChannels() {
        return rawWorkerMessageChannels;
    }

    public List<WorkerEndpointInspector> getEndpointInspectors() {
        return endpointInspectors;
    }

    public void validateAgainst(TransportAdapterDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        for (TransportBinding binding : transportBindings) {
            if (!descriptor.getAdapterId().equals(binding.getAdapterId())) {
                throw new IllegalStateException("Transport adapter descriptor adapterId '"
                        + descriptor.getAdapterId() + "' does not match contributed binding adapterId '"
                        + binding.getAdapterId() + "'");
            }
            if (!descriptor.getTransportHint().equals(binding.getTransportHint())) {
                throw new IllegalStateException("Transport adapter descriptor transportHint '"
                        + descriptor.getTransportHint() + "' does not match contributed binding transportHint '"
                        + binding.getTransportHint() + "' for adapterId '" + binding.getAdapterId() + "'");
            }
        }
    }

    public static final class Builder {
        private final List<TransportBinding> transportBindings = new ArrayList<>();
        private final List<ManagedTransportAdapter> managedTransportAdapters = new ArrayList<>();
        private final List<TransportServer> transportServers = new ArrayList<>();
        private final List<RawWorkerMessageChannel> rawWorkerMessageChannels = new ArrayList<>();
        private final List<WorkerEndpointInspector> endpointInspectors = new ArrayList<>();

        public Builder addTransportBinding(TransportBinding binding) {
            if (binding != null) {
                transportBindings.add(binding);
            }
            return this;
        }

        public Builder addManagedTransportAdapter(ManagedTransportAdapter adapter) {
            if (adapter != null) {
                managedTransportAdapters.add(adapter);
            }
            return this;
        }

        public Builder addTransportServer(TransportServer server) {
            if (server != null) {
                transportServers.add(server);
            }
            return this;
        }

        public Builder addRawWorkerMessageChannel(RawWorkerMessageChannel channel) {
            if (channel != null) {
                rawWorkerMessageChannels.add(channel);
            }
            return this;
        }

        public Builder addEndpointInspector(WorkerEndpointInspector inspector) {
            if (inspector != null) {
                endpointInspectors.add(inspector);
            }
            return this;
        }

        public TransportAdapterContribution build() {
            return new TransportAdapterContribution(this);
        }
    }
}
