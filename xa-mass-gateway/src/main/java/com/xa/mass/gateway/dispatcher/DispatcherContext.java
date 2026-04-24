package com.xa.mass.gateway.dispatcher;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestHandler;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Concrete gateway dispatch runtime context.
 */
public class DispatcherContext implements DispatchRuntimeContext {
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final WebSocketTransportFrameCodec frameCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final ControlEventRequestHandler controlEventRequestHandler;
    private final ControlEventResponseFrameSink controlEventResponseFrameSink;

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry endpointRegistry,
                             WebSocketTransportFrameCodec frameCodec,
                             TaskResultIngestChannel taskResultIngestChannel,
                             WorkerSystemEventChannel systemEventChannel,
                             ControlEventRequestHandler controlEventRequestHandler,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.frameCodec = frameCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
        this.controlEventRequestHandler = controlEventRequestHandler;
        this.controlEventResponseFrameSink = controlEventResponseFrameSink;
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

    @Override
    public ControlEventRequestHandler getControlEventRequestHandler() {
        return controlEventRequestHandler;
    }

    @Override
    public ControlEventResponseFrameSink getControlEventResponseFrameSink() {
        return controlEventResponseFrameSink;
    }
}
