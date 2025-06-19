package com.xa.mass.core.server;

import com.xa.mass.core.queue.Envelope;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.middleware.MessageMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.dispatcher.MessageHandler;
import com.xa.mass.core.session.ServerSessionManager;
import com.xa.mass.core.dispatcher.ServerMessageDispatcher;

import java.util.List;
import java.util.Map;

public class MassServer {
    private final int port;
    private final String websocketPath;
    private final MessageQueue<Envelope> inputQueue;
    private final MessageQueue<Envelope> outputQueue;
    private final Map<String, Map<MessageType, MessageHandler>> handlerMap;
    private final List<MessageMiddleware> inputMiddlewareList;
    private final List<MessageMiddleware> outputMiddlewareList;
    private final ServerSessionManager sessionManager;
    private final ServerMessageDispatcher dispatcher;

    private WebSocketServerImpl webSocketServer;

    public MassServer(
            int port,
            String websocketPath,
            MessageQueue<Envelope> inputQueue,
            MessageQueue<Envelope> outputQueue,
            Map<String, Map<MessageType, MessageHandler>> handlerMap,
            List<MessageMiddleware> inputMiddlewareList,
            List<MessageMiddleware> outputMiddlewareList,
            ServerSessionManager sessionManager,
            ServerMessageDispatcher dispatcher
    ) {
        this.port = port;
        this.websocketPath = websocketPath;
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.handlerMap = handlerMap;
        this.inputMiddlewareList = inputMiddlewareList;
        this.outputMiddlewareList = outputMiddlewareList;
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
    }

    public void start() {
        // 初始化 WebSocketServerImpl
        webSocketServer = new WebSocketServerImpl();
        webSocketServer.setPort(port);
        webSocketServer.setWebsocketPath(websocketPath);
        webSocketServer.setSessionManager(sessionManager);
        webSocketServer.setServerMessageDispatcher(dispatcher);
        webSocketServer.setInputQueue(inputQueue);
        webSocketServer.setOutputQueue(outputQueue);
        webSocketServer.setInputMiddlewareList(inputMiddlewareList);
        webSocketServer.setOutputMiddlewareList(outputMiddlewareList);
        webSocketServer.setHandlerMap(handlerMap);
        webSocketServer.start(port);
    }

    public void stop() {
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
    }
} 