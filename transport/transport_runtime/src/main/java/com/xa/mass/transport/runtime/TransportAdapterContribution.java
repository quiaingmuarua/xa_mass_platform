package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;

/**
 * Runtime contribution assembled by one concrete transport adapter.
 */
public final class TransportAdapterContribution {

    private static final TransportAdapterContribution EMPTY = builder().build();

    private final TransportBinding transportBinding;
    private final ManagedTransportAdapter managedTransportAdapter;
    private final TransportServer transportServer;
    private final RawWorkerMessageChannel rawWorkerMessageChannel;

    private TransportAdapterContribution(Builder builder) {
        this.transportBinding = builder.transportBinding;
        this.managedTransportAdapter = builder.managedTransportAdapter;
        this.transportServer = builder.transportServer;
        this.rawWorkerMessageChannel = builder.rawWorkerMessageChannel;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TransportAdapterContribution empty() {
        return EMPTY;
    }

    public TransportBinding getTransportBinding() {
        return transportBinding;
    }

    public ManagedTransportAdapter getManagedTransportAdapter() {
        return managedTransportAdapter;
    }

    public TransportServer getTransportServer() {
        return transportServer;
    }

    public RawWorkerMessageChannel getRawWorkerMessageChannel() {
        return rawWorkerMessageChannel;
    }

    public static final class Builder {
        private TransportBinding transportBinding;
        private ManagedTransportAdapter managedTransportAdapter;
        private TransportServer transportServer;
        private RawWorkerMessageChannel rawWorkerMessageChannel;

        private Builder() {
        }

        public Builder transportBinding(TransportBinding transportBinding) {
            this.transportBinding = transportBinding;
            return this;
        }

        public Builder managedTransportAdapter(ManagedTransportAdapter managedTransportAdapter) {
            this.managedTransportAdapter = managedTransportAdapter;
            return this;
        }

        public Builder transportServer(TransportServer transportServer) {
            this.transportServer = transportServer;
            return this;
        }

        public Builder rawWorkerMessageChannel(RawWorkerMessageChannel rawWorkerMessageChannel) {
            this.rawWorkerMessageChannel = rawWorkerMessageChannel;
            return this;
        }

        public TransportAdapterContribution build() {
            return new TransportAdapterContribution(this);
        }
    }
}
