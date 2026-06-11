package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.Objects;

/**
 * Transport-neutral runtime assembly context handed to adapter-owned bootstrap
 * code.
 */
public final class TransportAdapterBootstrapContext {

    private final WorkerEndpointRegistry endpointRegistry;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final TransportRouteOwnerStore routeOwnerStore;
    private final TransportDeliveryService deliveryService;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final TransportRouteKeyResolver routeKeyResolver;
    private TransportBinding transportBinding;
    private ManagedTransportAdapter managedTransportAdapter;
    private TransportServer transportServer;
    private RawWorkerMessageChannel rawWorkerMessageChannel;

    public TransportAdapterBootstrapContext(WorkerEndpointRegistry endpointRegistry,
                                            TaskResultIngestChannel taskResultIngestChannel,
                                            WorkerSystemEventChannel systemEventChannel,
                                            TransportRouteOwnerStore routeOwnerStore,
                                            TransportDeliveryService deliveryService,
                                            RuntimeTaskExecutor runtimeTaskExecutor,
                                            TransportRouteKeyResolver routeKeyResolver) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.routeOwnerStore = Objects.requireNonNull(routeOwnerStore, "routeOwnerStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
        this.routeKeyResolver = Objects.requireNonNull(routeKeyResolver, "routeKeyResolver");
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public TransportRouteOwnerStore getRouteOwnerStore() {
        return routeOwnerStore;
    }

    public TransportDeliveryService getDeliveryService() {
        return deliveryService;
    }

    public RuntimeTaskExecutor getRuntimeTaskExecutor() {
        return runtimeTaskExecutor;
    }

    public TransportRouteKeyResolver getRouteKeyResolver() {
        return routeKeyResolver;
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
}
