package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.Objects;

/**
 * Transport-neutral runtime assembly context handed to adapter-owned bootstrap
 * code.
 */
public final class TransportAdapterBootstrapContext {

    private final WorkerEndpointRegistry endpointRegistry;
    private final TransportResultIngressChannel resultIngressChannel;
    private final WorkerPresenceIngress workerPresenceIngress;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final TransportDeliveryService deliveryService;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportAdapterBootstrapContext(WorkerEndpointRegistry endpointRegistry,
                                            TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryService deliveryService,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this(endpointRegistry,
                resultIngressChannel,
                workerPresenceIngress,
                endpointLeaseStore,
                deliveryService,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                runtimeTaskExecutor);
    }

    public TransportAdapterBootstrapContext(WorkerEndpointRegistry endpointRegistry,
                                            TransportResultIngressChannel resultIngressChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryService deliveryService,
                                            DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.resultIngressChannel = resultIngressChannel;
        this.workerPresenceIngress = Objects.requireNonNull(workerPresenceIngress, "workerPresenceIngress");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
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

    public DeliveryCommandConsumerRegistry getDeliveryCommandConsumerRegistry() {
        return deliveryCommandConsumerRegistry;
    }

    public RuntimeTaskExecutor getRuntimeTaskExecutor() {
        return runtimeTaskExecutor;
    }

}
