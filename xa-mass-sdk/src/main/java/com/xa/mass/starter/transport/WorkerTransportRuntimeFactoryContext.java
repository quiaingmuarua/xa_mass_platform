package com.xa.mass.starter.transport;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Context passed when assembling worker transport bindings for the embedded
 * runtime.
 */
public final class WorkerTransportRuntimeFactoryContext {

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final WorkerSystemEventChannel systemEventChannel;
    private final boolean gatewayEnabled;

    public WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                WorkerManager workerManager,
                                                DispatchRuntimeContext dispatchRuntimeContext,
                                                WorkerSystemEventChannel systemEventChannel,
                                                boolean gatewayEnabled) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.dispatchRuntimeContext = dispatchRuntimeContext;
        this.systemEventChannel = systemEventChannel;
        this.gatewayEnabled = gatewayEnabled;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public DispatchRuntimeContext getDispatchRuntimeContext() {
        return dispatchRuntimeContext;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public boolean isGatewayEnabled() {
        return gatewayEnabled;
    }
}
