package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
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
    private final TransportDeliveryService deliveryService;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private TransportBinding transportBinding;
    private ManagedTransportAdapter managedTransportAdapter;
    private TransportServer transportServer;
    private RawWorkerMessageChannel rawWorkerMessageChannel;

    public TransportAdapterBootstrapContext(WorkerEndpointRegistry endpointRegistry,
                                            TaskResultIngestChannel taskResultIngestChannel,
                                            WorkerSystemEventChannel systemEventChannel,
                                            TransportDeliveryService deliveryService,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
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

    public TransportDeliveryService getDeliveryService() {
        return deliveryService;
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
}
