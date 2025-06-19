package com.xa.mass.core.getway.server;

import com.google.gson.Gson;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.model.message.enums.MessageType;

public class MassServerBuilder {
    private int port = 8080;
    private String websocketPath = "/ws";
    private MessageQueue<Envelope> inputQueue;
    private MessageQueue<Envelope> outputQueue;

    private final MiddlewareRegistry middlewareRegistry = MiddlewareRegistry.instance;

    private ServerSessionManager sessionManager;
    private ServerMessageDispatcher serverMessageDispatcher;
    private Gson gson = new Gson();

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

    public MassServerBuilder withInputQueue(MessageQueue<Envelope> queue) {
        this.inputQueue = queue;
        return this;
    }

    public MassServerBuilder withOutputQueue(MessageQueue<Envelope> queue) {
        this.outputQueue = queue;
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

    public MassServerBuilder withSessionManager(ServerSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        return this;
    }

    public MassServerBuilder withDispatcher(ServerMessageDispatcher serverMessageDispatcher){
        this.serverMessageDispatcher=serverMessageDispatcher;
        return this;
    }

    public MassServerBuilder withGson(Gson gson) {
        this.gson = gson;
        return this;
    }

    public MassServerBuilder withDefaultMiddlewares(boolean enable) {
        this.registerDefaults = enable;
        return this;
    }

    private void registerDefaultMiddlewares() {
        // 推荐默认 input middleware
        this.registerInputMiddleware(5, new com.xa.mass.core.getway.middleware.LegacyBusinessMiddleware(
            new com.xa.mass.core.getway.queue.MessageDecoder(),
            new com.xa.mass.core.getway.queue.MessageContextValidator()
        ));
        this.registerInputMiddleware(10, (envelope, context) -> {
            // 默认认证中间件
            // 可根据实际业务补充
            return true;
        });
        this.registerInputMiddleware(20, (envelope, context) -> {
            // 默认限流中间件
            // 可根据实际业务补充
            return true;
        });
        // 推荐默认 output middleware
        this.registerOutputMiddleware(10, (envelope, context) -> {
            // 默认输出日志中间件
            return true;
        });
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
            registerDefaultMiddlewares();
            // 如果 handlerMap 为空，注册一个 demo handler
        }
        DispatcherContext dispatcherContext = new DispatcherContext(
            inputQueue,
            outputQueue,
            sessionManager,
            gson,
                middlewareRegistry
        );
        return new MassServerConfig(
            port,
            websocketPath,
            dispatcherContext
        );
    }
} 