package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
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
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerPresenceIngress workerPresenceIngress;
    private final TransportRouteOwnerStore routeOwnerStore;
    private final TransportDeliveryService deliveryService;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final String deliveryCommandConsumerKey;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private TransportBinding transportBinding;
    private ManagedTransportAdapter managedTransportAdapter;
    private TransportServer transportServer;
    private RawWorkerMessageChannel rawWorkerMessageChannel;

    public TransportAdapterBootstrapContext(WorkerEndpointRegistry endpointRegistry,
                                            TaskResultIngestChannel taskResultIngestChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportRouteOwnerStore routeOwnerStore,
                                            TransportDeliveryService deliveryService,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this(endpointRegistry,
                taskResultIngestChannel,
                workerPresenceIngress,
                routeOwnerStore,
                deliveryService,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                "local",
                runtimeTaskExecutor);
    }

    public TransportAdapterBootstrapContext(WorkerEndpointRegistry endpointRegistry,
                                            TaskResultIngestChannel taskResultIngestChannel,
                                            WorkerPresenceIngress workerPresenceIngress,
                                            TransportRouteOwnerStore routeOwnerStore,
                                            TransportDeliveryService deliveryService,
                                            DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                            String deliveryCommandConsumerKey,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.workerPresenceIngress = Objects.requireNonNull(workerPresenceIngress, "workerPresenceIngress");
        this.routeOwnerStore = Objects.requireNonNull(routeOwnerStore, "routeOwnerStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.deliveryCommandConsumerKey = requireText(deliveryCommandConsumerKey, "deliveryCommandConsumerKey");
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerPresenceIngress getWorkerPresenceIngress() {
        return workerPresenceIngress;
    }

    public TransportRouteOwnerStore getRouteOwnerStore() {
        return routeOwnerStore;
    }

    public TransportDeliveryService getDeliveryService() {
        return deliveryService;
    }

    public DeliveryCommandConsumerRegistry getDeliveryCommandConsumerRegistry() {
        return deliveryCommandConsumerRegistry;
    }

    public String getDeliveryCommandConsumerKey() {
        return deliveryCommandConsumerKey;
    }

    public RuntimeTaskExecutor getRuntimeTaskExecutor() {
        return runtimeTaskExecutor;
    }

    public void registerTransportBinding(TransportBinding transportBinding) {
        this.transportBinding = transportBinding;
    }

    public TransportBinding getTransportBinding() {
        return transportBinding;
    }

    public void registerManagedTransportAdapter(ManagedTransportAdapter managedTransportAdapter) {
        this.managedTransportAdapter = managedTransportAdapter;
    }

    public ManagedTransportAdapter getManagedTransportAdapter() {
        return managedTransportAdapter;
    }

    public void registerTransportServer(TransportServer transportServer) {
        this.transportServer = transportServer;
    }

    public TransportServer getTransportServer() {
        return transportServer;
    }

    public void registerRawWorkerMessageChannel(RawWorkerMessageChannel rawWorkerMessageChannel) {
        this.rawWorkerMessageChannel = rawWorkerMessageChannel;
    }

    public RawWorkerMessageChannel getRawWorkerMessageChannel() {
        return rawWorkerMessageChannel;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
