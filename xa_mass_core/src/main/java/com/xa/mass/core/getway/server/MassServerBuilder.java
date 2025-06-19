package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.model.message.enums.MessageType;

public class MassServerBuilder {
    private int port = 8080;
    private String websocketPath = "/ws";
    private MessageQueue<Envelope> inputQueue;
    private MessageQueue<Envelope> outputQueue;

    private final MiddlewareRegistry middlewareRegistry = MiddlewareRegistry.instance;

    private DispatcherContext dispatcherContext;


    private boolean registerDefaults = true;

    private MassServerBuilder() {}

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

    public  MassServerBuilder setDispatcherContext(DispatcherContext dispatcherContext){
        this.dispatcherContext=dispatcherContext;
        return this;
    }

    public MassServerBuilder registerHandler(MessageType type, String subMsgType, MessageHandler handler) {
        MessageHandlerRegistry.register(type, subMsgType, handler);
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


    public MassServerBuilder withDefaultMiddlewares(boolean enable) {
        this.registerDefaults = enable;
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
        if (registerDefaults) {
            MiddlewareRegistry.autoRegister();
            // 如果 handlerMap 为空，注册一个 demo handler
        }
        return new MassServerConfig(
            port,
            websocketPath,
            dispatcherContext
        );
    }
} 