package com.xa.mass.core.server;

import com.xa.mass.core.queue.Envelope;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.middleware.MessageMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.dispatcher.MessageHandler;
import com.xa.mass.core.session.ServerSessionManager;
import com.xa.mass.core.dispatcher.ServerMessageDispatcher;

import java.util.*;

public class MassServerBuilder {
    private int port = 8080;
    private String websocketPath = "/ws";
    private MessageQueue<Envelope> inputQueue;
    private MessageQueue<Envelope> outputQueue;
    private final Map<String, Map<MessageType, MessageHandler>> handlerMap = new HashMap<>();
    private final List<MessageMiddleware> inputMiddlewareList = new ArrayList<>();
    private final List<MessageMiddleware> outputMiddlewareList = new ArrayList<>();
    private ServerSessionManager sessionManager;
    private ServerMessageDispatcher dispatcher;

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

    public MassServerBuilder withHandler(String appName, MessageType type, MessageHandler handler) {
        handlerMap.computeIfAbsent(appName, k -> new HashMap<>()).put(type, handler);
        return this;
    }

    public MassServerBuilder withInputMiddleware(MessageMiddleware middleware) {
        this.inputMiddlewareList.add(middleware);
        return this;
    }

    public MassServerBuilder withOutputMiddleware(MessageMiddleware middleware) {
        this.outputMiddlewareList.add(middleware);
        return this;
    }

    public MassServerBuilder withInputMiddlewareList(List<MessageMiddleware> middlewareList) {
        this.inputMiddlewareList.addAll(middlewareList);
        return this;
    }

    public MassServerBuilder withOutputMiddlewareList(List<MessageMiddleware> middlewareList) {
        this.outputMiddlewareList.addAll(middlewareList);
        return this;
    }

    public MassServerBuilder withSessionManager(ServerSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        return this;
    }

    public MassServerBuilder withDispatcher(ServerMessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        return this;
    }

    public MassServer build() {
        return new MassServer(
            port,
            websocketPath,
            inputQueue,
            outputQueue,
            handlerMap,
            inputMiddlewareList,
            outputMiddlewareList,
            sessionManager,
            dispatcher
        );
    }
} 