package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.Objects;

/**
 * Transport-neutral runtime inputs handed to adapter-owned bootstrap code.
 */
public final class TransportAdapterBootstrapContext<T> {

    private final WorkerEndpointRegistry endpointRegistry;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final TransportDeliveryService deliveryService;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

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
}
