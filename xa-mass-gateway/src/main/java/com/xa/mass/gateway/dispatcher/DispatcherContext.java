package com.xa.mass.gateway.dispatcher;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Concrete gateway dispatch runtime context.
 */
public class DispatcherContext implements DispatchRuntimeContext {
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final MessageCodec messageCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final ControlEventRequestFrameBridge controlEventRequestFrameBridge;
    private final ControlEventResponseFrameSink controlEventResponseFrameSink;

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry endpointRegistry,
                             MessageCodec messageCodec,
                             TaskResultIngestChannel taskResultIngestChannel,
                             WorkerSystemEventChannel systemEventChannel,
                             ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.messageCodec = messageCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
        this.controlEventRequestFrameBridge = controlEventRequestFrameBridge;
        this.controlEventResponseFrameSink = controlEventResponseFrameSink;
    }

    @Override
    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    @Override
    public MessageCodec getMessageCodec() {
        return messageCodec;
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
    public ControlEventRequestFrameBridge getControlEventRequestFrameBridge() {
        return controlEventRequestFrameBridge;
    }

    @Override
    public ControlEventResponseFrameSink getControlEventResponseFrameSink() {
        return controlEventResponseFrameSink;
    }
}
