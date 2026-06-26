package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit output emitted by one adapter bootstrap.
 *
 * <p>Runtime inputs live in {@link TransportAdapterBootstrapContext}. This
 * contribution object owns adapter-produced runtime outputs so bootstrap
 * assembly cannot silently overwrite bindings or servers in mutable context
 * state.
 */
public final class TransportAdapterContribution {

    private static final TransportAdapterContribution EMPTY = builder().build();

    private final List<TransportBinding> transportBindings;
    private final List<AdapterMailboxConsumer> adapterMailboxConsumers;
    private final List<ManagedTransportAdapter> managedTransportAdapters;
    private final List<TransportServer> transportServers;

    private TransportAdapterContribution(Builder builder) {
        this.transportBindings = List.copyOf(builder.transportBindings);
        this.adapterMailboxConsumers = List.copyOf(builder.adapterMailboxConsumers);
        this.managedTransportAdapters = List.copyOf(builder.managedTransportAdapters);
        this.transportServers = List.copyOf(builder.transportServers);
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

    public List<AdapterMailboxConsumer> getAdapterMailboxConsumers() {
        return adapterMailboxConsumers;
    }

    public List<TransportServer> getTransportServers() {
        return transportServers;
    }

    public void validateAgainst(TransportAdapterDescriptor descriptor, String assignedMailboxKey) {
        String mailboxKey = requireText(assignedMailboxKey, "assignedMailboxKey");
        for (TransportBinding binding : transportBindings) {
            if (descriptor != null) {
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
            if (!mailboxKey.equals(binding.getAdapterMailboxKey())) {
                throw new IllegalStateException("Transport adapter assigned mailbox key '"
                        + mailboxKey + "' does not match contributed binding mailbox key '"
                        + binding.getAdapterMailboxKey() + "' for adapterId '" + binding.getAdapterId() + "'");
            }
        }
        for (AdapterMailboxConsumer consumer : adapterMailboxConsumers) {
            if (!mailboxKey.equals(consumer.adapterMailboxKey())) {
                throw new IllegalStateException("Transport adapter assigned mailbox key '"
                        + mailboxKey + "' does not match contributed mailbox consumer key '"
                        + consumer.adapterMailboxKey() + "'");
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private final List<TransportBinding> transportBindings = new ArrayList<>();
        private final List<AdapterMailboxConsumer> adapterMailboxConsumers = new ArrayList<>();
        private final List<ManagedTransportAdapter> managedTransportAdapters = new ArrayList<>();
        private final List<TransportServer> transportServers = new ArrayList<>();

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

        public Builder addAdapterMailboxConsumer(AdapterMailboxConsumer consumer) {
            if (consumer != null) {
                adapterMailboxConsumers.add(consumer);
            }
            return this;
        }

        public Builder addTransportServer(TransportServer server) {
            if (server != null) {
                transportServers.add(server);
            }
            return this;
        }

        public TransportAdapterContribution build() {
            return new TransportAdapterContribution(this);
        }
    }
}
