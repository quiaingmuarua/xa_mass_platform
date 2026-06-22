package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxClient;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumer;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumerLoop;
import com.xa.mass.transport.runtime.embedded.DeliveryFailureEvidenceSink;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;

import java.util.Objects;

/**
 * Host-provided adapter bootstrap capability surface.
 */
public final class TransportAdapterBootstrapContext implements AdapterBootstrapCapabilities {

    private final AdapterBootstrapAssignment assignment;
    private final TransportResultIngressChannel resultIngressChannel;
    private final WorkerPresenceIngress workerPresenceIngress;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final AdapterMailboxClient adapterMailboxClient;
    private final DeliveryFailureEvidenceSink failureEvidenceSink;
    private final AdapterMailboxConsumerRegistry mailboxConsumerRegistry;
    private final long mailboxConsumerAvailabilityMillis;
    private final AdapterMailboxCapabilities mailboxCapabilities = new BootstrapMailboxCapabilities();
    private final AdapterSessionEvidenceCapabilities sessionEvidenceCapabilities =
            new BootstrapSessionEvidenceCapabilities();
    private final AdapterIngressCapabilities ingressCapabilities = new BootstrapIngressCapabilities();
    private final AdapterHostResources hostResources = new BootstrapHostResources();

    public TransportAdapterBootstrapContext(TransportAdapterDescriptor descriptor,
                                            String adapterMailboxKey,
                                            TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            RuntimeTaskExecutor runtimeTaskExecutor,
                                            AdapterMailboxClient adapterMailboxClient,
                                            DeliveryFailureEvidenceSink failureEvidenceSink,
                                            AdapterMailboxConsumerRegistry mailboxConsumerRegistry,
                                            long mailboxConsumerAvailabilityMillis) {
        this.assignment = new AdapterBootstrapAssignment(descriptor, adapterMailboxKey);
        this.resultIngressChannel = resultIngressChannel;
        this.workerPresenceIngress = Objects.requireNonNull(workerPresenceIngress, "workerPresenceIngress");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
        this.adapterMailboxClient = adapterMailboxClient;
        this.failureEvidenceSink = failureEvidenceSink != null ? failureEvidenceSink : ignored -> { };
        this.mailboxConsumerRegistry = mailboxConsumerRegistry != null
                ? mailboxConsumerRegistry
                : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        if (mailboxConsumerAvailabilityMillis <= 0L) {
            throw new IllegalArgumentException("mailboxConsumerAvailabilityMillis must be greater than 0");
        }
        this.mailboxConsumerAvailabilityMillis = mailboxConsumerAvailabilityMillis;
    }

    @Override
    public AdapterBootstrapAssignment assignment() {
        return assignment;
    }

    @Override
    public AdapterMailboxCapabilities mailbox() {
        return mailboxCapabilities;
    }

    @Override
    public AdapterSessionEvidenceCapabilities sessionEvidence() {
        return sessionEvidenceCapabilities;
    }

    @Override
    public AdapterIngressCapabilities ingress() {
        return ingressCapabilities;
    }

    @Override
    public AdapterHostResources hostResources() {
        return hostResources;
    }

    private AdapterMailboxConsumer adapterMailboxConsumer(String consumerId,
                                                          AdapterCommandExecutor commandExecutor) {
        if (adapterMailboxClient == null) {
            return null;
        }
        String adapterMailboxKey = assignment.adapterMailboxKey();
        return new AdapterMailboxConsumerLoop(
                adapterMailboxKey,
                adapterMailboxClient,
                commandExecutor,
                failureEvidenceSink,
                mailboxConsumerAvailabilityPublisher(adapterMailboxKey, consumerId),
                runtimeTaskExecutor
        );
    }

    private MailboxConsumerAvailabilityPublisher mailboxConsumerAvailabilityPublisher(String adapterMailboxKey,
                                                                                     String consumerId) {
        return new MailboxConsumerAvailabilityPublisher(
                adapterMailboxKey,
                consumerId,
                mailboxConsumerRegistry,
                mailboxConsumerAvailabilityMillis,
                runtimeTaskExecutor
        );
    }

    private final class BootstrapMailboxCapabilities implements AdapterMailboxCapabilities {

        @Override
        public String assignedMailboxKey() {
            return assignment.adapterMailboxKey();
        }

        @Override
        public AdapterMailboxConsumer consumer(String consumerId, AdapterCommandExecutor commandExecutor) {
            return adapterMailboxConsumer(consumerId, commandExecutor);
        }
    }

    private final class BootstrapSessionEvidenceCapabilities implements AdapterSessionEvidenceCapabilities {

        @Override
        public AdapterSessionEvidencePublisher publisher() {
            return new AdapterSessionEvidencePublisher(
                    assignment.adapterId(),
                    assignment.adapterMailboxKey(),
                    endpointLeaseStore,
                    workerPresenceIngress
            );
        }
    }

    private final class BootstrapIngressCapabilities implements AdapterIngressCapabilities {

        @Override
        public AdapterResultIngressSink resultIngress() {
            if (resultIngressChannel == null) {
                return null;
            }
            return resultIngressChannel::ingest;
        }
    }

    private final class BootstrapHostResources implements AdapterHostResources {

        @Override
        public AdapterHostExecutor executor() {
            return runtimeTaskExecutor::submit;
        }
    }

}
