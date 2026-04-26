package com.xa.mass.transport.runtime;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
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

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final TransportDeliveryService deliveryService;
    private final List<TransportBinding> adapterBindings;

    public WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                WorkerManager workerManager,
                                                TaskResultIngestChannel taskResultIngestChannel,
                                                WorkerSystemEventChannel systemEventChannel,
                                                TransportDeliveryService deliveryService,
                                                List<TransportBinding> adapterBindings) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.adapterBindings = List.copyOf(adapterBindings);
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public WorkerManager getWorkerManager() {
        return workerManager;
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
