package com.xa.mass.starter.transport;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Context passed when assembling worker transport bindings for the embedded
 * runtime.
 */
public final class WorkerTransportRuntimeFactoryContext {

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final WebSocketGatewayFrameCodec frameCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final boolean gatewayEnabled;

    public WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                WorkerManager workerManager,
                                                MessageTransporter<String, OutboundDelivery> messageTransporter,
                                                WorkerEndpointRegistry endpointRegistry,
                                                WebSocketGatewayFrameCodec frameCodec,
                                                TaskResultIngestChannel taskResultIngestChannel,
                                                WorkerSystemEventChannel systemEventChannel,
                                                boolean gatewayEnabled) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.frameCodec = frameCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
        this.gatewayEnabled = gatewayEnabled;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public MessageTransporter<String, OutboundDelivery> getMessageTransporter() {
        return messageTransporter;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public WebSocketGatewayFrameCodec getFrameCodec() {
        return frameCodec;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public boolean isGatewayEnabled() {
        return gatewayEnabled;
    }
}
