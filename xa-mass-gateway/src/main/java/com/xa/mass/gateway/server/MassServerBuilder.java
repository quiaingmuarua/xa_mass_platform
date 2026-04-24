package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.middleware.EnvelopeMiddleware;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.model.enums.MessageType;

public class MassServerBuilder {
    private final MiddlewareRegistry middlewareRegistry = MiddlewareRegistry.instance;
    private int port = 8080;
    private String websocketPath = "/ws";
    private DispatchRuntimeContext dispatcherContext;

    private MassServerBuilder() {
    }

    public static MassServerBuilder create() {
        return new MassServerBuilder();
    }

    public MassServerBuilder withPort(int port) {
        this.port = port;
        return this;
    }

    public MassServerBuilder withWebSocketPath(String path) {
        this.websocketPath = path;
        return this;
    }

    public MassServerBuilder withDispatcherContext(DispatchRuntimeContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
        return this;
    }

    /**
     * Registers a protocol-frame handler for the current gateway adapter.
     *
     * <p>This builder only configures wire-frame handlers. New business or
     * control capabilities must be added through global SDK event definitions,
     * not by introducing new tuple identities here.
     */
    public MassServerBuilder registerHandler(MessageType type, String subMsgType, MassMessageHandler handler) {
        if (dispatcherContext == null || dispatcherContext.getMessageHandlerRegistry() == null) {
            throw new IllegalStateException("DispatcherContext with MessageHandlerRegistry must be provided before registering handlers.");
        }
        dispatcherContext.getMessageHandlerRegistry().register(type, subMsgType, handler);
        return this;
    }

    public MassServerBuilder registerInputMiddleware(int priority, EnvelopeMiddleware mw) {
        middlewareRegistry.registerInput(priority, mw);
        return this;
    }

    public MassServerBuilder unregisterInputMiddleware(int priority) {
        middlewareRegistry.unregisterInput(priority);
        return this;
    }

    public MassServerBuilder setInputMiddlewareEnabled(int priority, boolean enabled) {
        middlewareRegistry.setInputEnabled(priority, enabled);
        return this;
    }

    public MassServerBuilder registerOutputMiddleware(int priority, EnvelopeMiddleware mw) {
        middlewareRegistry.registerOutput(priority, mw);
        return this;
    }

    public MassServerBuilder unregisterOutputMiddleware(int priority) {
        middlewareRegistry.unregisterOutput(priority);
        return this;
    }

    public MassServerBuilder setOutputMiddlewareEnabled(int priority, boolean enabled) {
        middlewareRegistry.setOutputEnabled(priority, enabled);
        return this;
    }
    public MassServerBuilder removeInputMiddleware(int priority) {
        middlewareRegistry.unregisterInput(priority);
        return this;
    }

    public MassServerBuilder removeOutputMiddleware(int priority) {
        middlewareRegistry.unregisterOutput(priority);
        return this;
    }

    public MassServerConfig build() {
        if (this.dispatcherContext == null) {
            throw new IllegalStateException("DispatcherContext must be provided.");
        }

        return new MassServerConfig(
                port,
                websocketPath,
                dispatcherContext
        );
    }
} 
