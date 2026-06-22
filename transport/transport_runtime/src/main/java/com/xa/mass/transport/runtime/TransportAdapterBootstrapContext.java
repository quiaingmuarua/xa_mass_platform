package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.AdapterPullDeliveryBuffer;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
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
    private final TransportDeliveryService deliveryService;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportAdapterBootstrapContext(TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryService deliveryService,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.resultIngressChannel = resultIngressChannel;
        this.workerPresenceIngress = Objects.requireNonNull(workerPresenceIngress, "workerPresenceIngress");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
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

    public AdapterPullDeliveryBuffer pullDeliveryBuffer(String adapterMailboxKey) {
        return new AdapterPullDeliveryBuffer(adapterMailboxKey, deliveryService);
    }

    public RuntimeTaskExecutor getRuntimeTaskExecutor() {
        return runtimeTaskExecutor;
    }

}
