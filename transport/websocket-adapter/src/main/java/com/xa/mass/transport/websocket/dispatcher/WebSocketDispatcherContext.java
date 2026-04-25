package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.queue.OutboundDelivery;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Concrete WebSocket adapter dispatch runtime context.
 */
public class WebSocketDispatcherContext implements WebSocketDispatchRuntimeContext {
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final WebSocketTransportFrameCodec frameCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;

    public WebSocketDispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry endpointRegistry,
                             WebSocketTransportFrameCodec frameCodec,
                             TaskResultIngestChannel taskResultIngestChannel,
                             WorkerSystemEventChannel systemEventChannel) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.frameCodec = frameCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
    }

    @Override
    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    @Override
    public WebSocketTransportFrameCodec getFrameCodec() {
        return frameCodec;
    }

    @Override
    public MessageTransporter<String, OutboundDelivery> getMessageTransporter() {
        return messageTransporter;
    }

    @Override
    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    @Override
    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }
}
