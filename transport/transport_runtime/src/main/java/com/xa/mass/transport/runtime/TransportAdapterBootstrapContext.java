package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

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
    private final AdapterMailboxConsumerRegistry adapterMailboxConsumerRegistry;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportAdapterBootstrapContext(TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryService deliveryService,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this(resultIngressChannel,
                workerPresenceIngress,
                endpointLeaseStore,
                deliveryService,
                NoopAdapterMailboxConsumerRegistry.INSTANCE,
                runtimeTaskExecutor);
    }

    public TransportAdapterBootstrapContext(TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryService deliveryService,
                                            AdapterMailboxConsumerRegistry adapterMailboxConsumerRegistry,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.resultIngressChannel = resultIngressChannel;
        this.workerPresenceIngress = Objects.requireNonNull(workerPresenceIngress, "workerPresenceIngress");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.adapterMailboxConsumerRegistry = adapterMailboxConsumerRegistry != null
                ? adapterMailboxConsumerRegistry
                : NoopAdapterMailboxConsumerRegistry.INSTANCE;
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
    }

    public TransportResultIngressChannel getResultIngressChannel() {
        return resultIngressChannel;
    }

    public WorkerPresenceIngress getWorkerPresenceIngress() {
        return workerPresenceIngress;
    }

    public TransportEndpointLeaseStore getEndpointLeaseStore() {
        return endpointLeaseStore;
    }

    public TransportDeliveryService getDeliveryService() {
        return deliveryService;
    }

    public AdapterMailboxConsumerRegistry getAdapterMailboxConsumerRegistry() {
        return adapterMailboxConsumerRegistry;
    }

    public RuntimeTaskExecutor getRuntimeTaskExecutor() {
        return runtimeTaskExecutor;
    }

}
