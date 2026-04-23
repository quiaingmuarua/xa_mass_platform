package com.xa.mass.gateway.server;

import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.handler.MessageRouterKeys;
import com.xa.mass.gateway.dispatcher.middleware.EnvelopeMiddleware;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.model.enums.MessageType;

import java.util.HashMap;
import java.util.Map;

public class MassServerBuilder {
    private final MiddlewareRegistry middlewareRegistry = MiddlewareRegistry.instance;
    private final Map<String, Map<String, MassMessageHandler>> handlerMap = new HashMap<>();
    private int port = 8080;
    private String websocketPath = "/ws";
    private DispatchRuntimeContext dispatcherContext;
    private boolean registerDefaults = true;

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
    public MassServerBuilder registerHandler(String project, MessageType type, String subMsgType, MassMessageHandler handler) {
        if (type == MessageType.CONTROL
                && WorkerControlEventProtocol.SUB_MSG_TYPE.equals(subMsgType)) {
            throw new IllegalArgumentException(
                    "CONTROL/event is reserved for the worker-control compatibility bridge; "
                            + "register a global SDK event handler instead of a tuple handler"
            );
        }
        String proj = (project == null || project.trim().isEmpty()) ? "GLOBAL" : project;
        String key = MessageRouterKeys.of(type, subMsgType);
        handlerMap.computeIfAbsent(proj, k -> new HashMap<>()).put(key, handler);
        return this;
    }

    public MassServerBuilder registerHandler(MessageType type, String subMsgType, MassMessageHandler handler) {
        return registerHandler(null, type, subMsgType, handler);
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
        if (this.dispatcherContext == null) {
            throw new IllegalStateException("DispatcherContext must be provided.");
        }

        // 注册自定义处理器（如果有的话）
        MessageHandlerRegistry messageHandlerRegistry = dispatcherContext.getMessageHandlerRegistry();
        if (messageHandlerRegistry != null) {
            handlerMap.forEach((project, map) -> {
                map.forEach((key, handler) -> {
                    String[] parts = key.split(":", 2);
                    MessageType type = MessageType.valueOf(parts[0]);
                    String subType = parts.length > 1 ? parts[1] : "";
                    messageHandlerRegistry.register(project, type, subType, handler);
                });
            });
        }

        return new MassServerConfig(
                port,
                websocketPath,
                dispatcherContext
        );
    }
} 
