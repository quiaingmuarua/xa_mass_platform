package com.xa.mass.starter.transport;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Context passed when assembling worker transport bindings for the embedded
 * runtime.
 */
public final class WorkerTransportRuntimeFactoryContext<T> {

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final MessageTransporter<String, T> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final TaskDispatchChannel taskDispatchChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final boolean webSocketEnabled;

    public WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                WorkerManager workerManager,
                                                MessageTransporter<String, T> messageTransporter,
                                                WorkerEndpointRegistry endpointRegistry,
                                                TaskDispatchChannel taskDispatchChannel,
                                                TaskResultIngestChannel taskResultIngestChannel,
                                                WorkerSystemEventChannel systemEventChannel,
                                                boolean webSocketEnabled) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.taskDispatchChannel = taskDispatchChannel;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
        this.webSocketEnabled = webSocketEnabled;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public MessageTransporter<String, T> getMessageTransporter() {
        return messageTransporter;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public TaskDispatchChannel getTaskDispatchChannel() {
        return taskDispatchChannel;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public boolean isWebSocketEnabled() {
        return webSocketEnabled;
    }
}
