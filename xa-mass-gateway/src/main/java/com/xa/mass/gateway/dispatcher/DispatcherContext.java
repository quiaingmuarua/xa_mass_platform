package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.WorkerEndpointRegistry;

/**
 * Concrete gateway dispatch runtime context.
 */
public class DispatcherContext implements DispatchRuntimeContext {
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry sessionManager;
    private final MessageCodec messageCodec;
    private final MiddlewareRegistry middlewareRegistry;
    private final GatewayFrameRouter frameRouter;
    private final TaskStepFrameBridge taskStepFrameBridge;
    private final ControlEventRequestFrameBridge controlEventRequestFrameBridge;
    private final ControlEventResponseFrameSink controlEventResponseFrameSink;

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry sessionManager,
                             MessageCodec messageCodec,
                             GatewayFrameRouter frameRouter,
                             TaskStepFrameBridge taskStepFrameBridge,
                             ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this(
                messageTransporter,
                sessionManager,
                messageCodec,
                new MiddlewareRegistry(),
                frameRouter,
                taskStepFrameBridge,
                controlEventRequestFrameBridge,
                controlEventResponseFrameSink
        );
    }

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry sessionManager,
                             MessageCodec messageCodec,
                             MiddlewareRegistry middlewareRegistry,
                             GatewayFrameRouter frameRouter,
                             TaskStepFrameBridge taskStepFrameBridge,
                             ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this.messageTransporter = messageTransporter;
        this.sessionManager = sessionManager;
        this.messageCodec = messageCodec;
        this.middlewareRegistry = middlewareRegistry;
        this.frameRouter = frameRouter;
        this.taskStepFrameBridge = taskStepFrameBridge;
        this.controlEventRequestFrameBridge = controlEventRequestFrameBridge;
        this.controlEventResponseFrameSink = controlEventResponseFrameSink;
    }

    public DispatcherContext(MessageTransporter<String, OutboundDelivery> messageTransporter,
                             WorkerEndpointRegistry sessionManager,
                             Gson gson,
                             GatewayFrameRouter frameRouter,
                             TaskStepFrameBridge taskStepFrameBridge,
                             ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                             ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this(
                messageTransporter,
                sessionManager,
                new GsonMessageCodec(gson),
                frameRouter,
                taskStepFrameBridge,
                controlEventRequestFrameBridge,
                controlEventResponseFrameSink
        );
    }

    @Override
    public WorkerEndpointRegistry getSessionManager() {
        return sessionManager;
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
    public MiddlewareRegistry getMiddlewareRegistry() {
        return middlewareRegistry;
    }

    @Override
    public GatewayFrameRouter getFrameRouter() {
        return frameRouter;
    }

    @Override
    public TaskStepFrameBridge getTaskStepFrameBridge() {
        return taskStepFrameBridge;
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
