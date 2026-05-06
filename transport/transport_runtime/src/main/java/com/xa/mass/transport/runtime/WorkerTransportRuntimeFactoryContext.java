package com.xa.mass.transport.runtime;

import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.List;
import java.util.Objects;

/**
 * Context passed when assembling worker transport bindings for the embedded
 * runtime.
 */
public final class WorkerTransportRuntimeFactoryContext {

    private final WorkerLookupStore workerLookupStore;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final TransportDeliveryService deliveryService;
    private final List<TransportBinding> adapterBindings;

    public WorkerTransportRuntimeFactoryContext(WorkerLookupStore workerLookupStore,
                                                TaskResultIngestChannel taskResultIngestChannel,
                                                WorkerSystemEventChannel systemEventChannel,
                                                TransportDeliveryService deliveryService,
                                                List<TransportBinding> adapterBindings) {
        this.workerLookupStore = Objects.requireNonNull(workerLookupStore, "workerLookupStore");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.adapterBindings = List.copyOf(adapterBindings);
    }

    public WorkerLookupStore getWorkerLookupStore() {
        return workerLookupStore;
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

    public List<TransportBinding> getAdapterBindings() {
        return adapterBindings;
    }
}
