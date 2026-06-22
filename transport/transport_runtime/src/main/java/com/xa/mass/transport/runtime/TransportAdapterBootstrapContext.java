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
 * Transport-neutral runtime assembly context handed to adapter-owned bootstrap
 * code.
 */
public final class TransportAdapterBootstrapContext {

    private final TransportResultIngressChannel resultIngressChannel;
    private final WorkerPresenceIngress workerPresenceIngress;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final AdapterMailboxClient adapterMailboxClient;
    private final DeliveryFailureEvidenceSink failureEvidenceSink;
    private final AdapterMailboxConsumerRegistry mailboxConsumerRegistry;
    private final long mailboxConsumerAvailabilityMillis;

    public TransportAdapterBootstrapContext(TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            RuntimeTaskExecutor runtimeTaskExecutor,
                                            AdapterMailboxClient adapterMailboxClient,
                                            DeliveryFailureEvidenceSink failureEvidenceSink,
                                            AdapterMailboxConsumerRegistry mailboxConsumerRegistry,
                                            long mailboxConsumerAvailabilityMillis) {
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

    public TransportResultIngressChannel getResultIngressChannel() {
        return resultIngressChannel;
    }

    public String adapterMailboxKey(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim();
    }

    public AdapterSessionEvidencePublisher sessionEvidencePublisher(String adapterId, String adapterMailboxKey) {
        return new AdapterSessionEvidencePublisher(
                adapterId,
                adapterMailboxKey,
                endpointLeaseStore,
                workerPresenceIngress
        );
    }

    public RuntimeTaskExecutor getRuntimeTaskExecutor() {
        return runtimeTaskExecutor;
    }

    public AdapterMailboxConsumer adapterMailboxConsumer(String adapterMailboxKey,
                                                         String consumerId,
                                                         AdapterCommandExecutor commandExecutor) {
        if (adapterMailboxClient == null) {
            return null;
        }
        return new AdapterMailboxConsumerLoop(
                adapterMailboxKey,
                adapterMailboxClient,
                commandExecutor,
                failureEvidenceSink,
                mailboxConsumerAvailabilityPublisher(adapterMailboxKey, consumerId),
                runtimeTaskExecutor
        );
    }

    public MailboxConsumerAvailabilityPublisher mailboxConsumerAvailabilityPublisher(String adapterMailboxKey,
                                                                                    String consumerId) {
        return new MailboxConsumerAvailabilityPublisher(
                adapterMailboxKey,
                consumerId,
                mailboxConsumerRegistry,
                mailboxConsumerAvailabilityMillis,
                runtimeTaskExecutor
        );
    }

}
