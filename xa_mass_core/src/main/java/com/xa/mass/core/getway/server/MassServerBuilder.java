package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.google.gson.Gson;

import java.util.*;

public class MassServerBuilder {
    private int port = 8080;
    private String websocketPath = "/ws";
    private MessageQueue<Envelope> inputQueue;
    private MessageQueue<Envelope> outputQueue;
    private final Map<String, Map<MessageType, MessageHandler>> handlerMap = new HashMap<>();
    private final List<EnvelopeMiddleware> inputMiddlewareList = new ArrayList<>();
    private final List<EnvelopeMiddleware> outputMiddlewareList = new ArrayList<>();

    private ServerSessionManager sessionManager;
    private ServerMessageDispatcher serverMessageDispatcher;
    private Gson gson = new Gson();

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

    public MassServerBuilder withInputMiddleware(EnvelopeMiddleware middleware) {
        this.inputMiddlewareList.add(middleware);
        return this;
    }

    public MassServerBuilder withOutputMiddleware(EnvelopeMiddleware middleware) {
        this.outputMiddlewareList.add(middleware);
        return this;
    }

    public MassServerBuilder withInputMiddlewareList(List<EnvelopeMiddleware> middlewareList) {
        this.inputMiddlewareList.addAll(middlewareList);
        return this;
    }

    public MassServerBuilder withOutputMiddlewareList(List<EnvelopeMiddleware> middlewareList) {
        this.outputMiddlewareList.addAll(middlewareList);
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

    public MassServerConfig build() {
        DispatcherContext dispatcherContext = new DispatcherContext(
            inputQueue,
            outputQueue,
            sessionManager,
            gson
        );
        return new MassServerConfig(
            port,
            websocketPath,
            dispatcherContext
        );
    }
} 