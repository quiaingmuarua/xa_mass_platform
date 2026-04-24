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
import com.xa.mass.gateway.queue.MessageParser;
import com.xa.mass.transport.WorkerEndpointRegistry;

/**
 * Concrete gateway dispatch runtime context.
 */
public class DispatcherContext implements DispatchRuntimeContext {
    private final MessageTransporter messageTransporter;
    private final WorkerEndpointRegistry sessionManager;
    private final MessageCodec messageCodec;
    private final MessageParser messageParser;
    private final MiddlewareRegistry middlewareRegistry;
    private GatewayFrameRouter frameRouter;
    private TaskStepFrameBridge taskStepFrameBridge;
    private ControlEventRequestFrameBridge controlEventRequestFrameBridge;
    private ControlEventResponseFrameSink controlEventResponseFrameSink;

    // Keep gateway runtime dependencies explicit on the context instead of using globals.
    public DispatcherContext(
            MessageTransporter messageTransporter,
            WorkerEndpointRegistry sessionManager,
            MessageCodec messageCodec
    ) {
        this(messageTransporter, sessionManager, messageCodec, new MessageParser(messageCodec), new MiddlewareRegistry());
    }

    public DispatcherContext(
            MessageTransporter messageTransporter,
            WorkerEndpointRegistry sessionManager,
            MessageCodec messageCodec,
            MessageParser messageParser,
            MiddlewareRegistry middlewareRegistry
    ) {
        this.messageTransporter = messageTransporter;
        this.sessionManager = sessionManager;
        this.messageCodec = messageCodec;
        this.messageParser = messageParser;
        this.middlewareRegistry = middlewareRegistry;
    }

    /**
     * Convenience constructor for callers that still bootstrap from a Gson instance.
     */
    public DispatcherContext(
            MessageTransporter messageTransporter,
            WorkerEndpointRegistry sessionManager,
            Gson gson
    ) {
        this(messageTransporter, sessionManager, new GsonMessageCodec(gson));
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
    public MessageParser getMessageParser() {
        return messageParser;
    }

    @Override
    public MessageTransporter getMessageTransporter() {
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
    public void setFrameRouter(GatewayFrameRouter frameRouter) {
        this.frameRouter = frameRouter;
    }

    @Override
    public TaskStepFrameBridge getTaskStepFrameBridge() {
        return taskStepFrameBridge;
    }

    @Override
    public void setTaskStepFrameBridge(TaskStepFrameBridge taskStepFrameBridge) {
        this.taskStepFrameBridge = taskStepFrameBridge;
    }

    @Override
    public ControlEventRequestFrameBridge getControlEventRequestFrameBridge() {
        return controlEventRequestFrameBridge;
    }

    @Override
    public void setControlEventRequestFrameBridge(ControlEventRequestFrameBridge controlEventRequestFrameBridge) {
        this.controlEventRequestFrameBridge = controlEventRequestFrameBridge;
    }

    @Override
    public ControlEventResponseFrameSink getControlEventResponseFrameSink() {
        return controlEventResponseFrameSink;
    }

    @Override
    public void setControlEventResponseFrameSink(ControlEventResponseFrameSink controlEventResponseFrameSink) {
        this.controlEventResponseFrameSink = controlEventResponseFrameSink;
    }
}
