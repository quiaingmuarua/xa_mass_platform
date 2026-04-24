package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;

/**
 * Concrete gateway dispatch runtime context.
 */
public class DispatcherContext implements DispatchRuntimeContext {
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final MessageCodec messageCodec;
    private final GatewayCompatibilityFrameClassifier compatibilityFrameClassifier;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final ControlEventRequestFrameBridge controlEventRequestFrameBridge;
    private final ControlEventResponseFrameSink controlEventResponseFrameSink;

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry endpointRegistry,
                             MessageCodec messageCodec,
                             GatewayCompatibilityFrameClassifier compatibilityFrameClassifier,
                             TaskResultIngestChannel taskResultIngestChannel,
                             ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.messageCodec = messageCodec;
        this.compatibilityFrameClassifier = compatibilityFrameClassifier;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.controlEventRequestFrameBridge = controlEventRequestFrameBridge;
        this.controlEventResponseFrameSink = controlEventResponseFrameSink;
    }

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry endpointRegistry,
                             Gson gson,
                             GatewayCompatibilityFrameClassifier compatibilityFrameClassifier,
                             TaskResultIngestChannel taskResultIngestChannel,
                             ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this(
                messageTransporter,
                endpointRegistry,
                new GsonMessageCodec(gson),
                compatibilityFrameClassifier,
                taskResultIngestChannel,
                controlEventRequestFrameBridge,
                controlEventResponseFrameSink
        );
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
    public GatewayCompatibilityFrameClassifier getCompatibilityFrameClassifier() {
        return compatibilityFrameClassifier;
    }

    @Override
    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
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
