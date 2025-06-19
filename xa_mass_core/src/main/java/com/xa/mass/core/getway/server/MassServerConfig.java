package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.middleware.MessageMiddleware;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.getway.dispatcher.MessageHandler;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;

import java.util.List;
import java.util.Map;

public class MassServerConfig {
    private final int port;
    private final String websocketPath;
    private final ServerSessionManager sessionManager;
    private final ServerMessageHandler serverMessageHandler;

    private WebSocketServerImpl webSocketServer;

    public MassServerConfig(
            int port,
            String websocketPath,
            ServerSessionManager sessionManager,
            ServerMessageHandler serverMessageHandler
    ) {
        this.port = port;
        this.websocketPath = websocketPath;
        this.sessionManager = sessionManager;
        this.serverMessageHandler = serverMessageHandler;
    }

    public void start() {
        // 初始化 WebSocketServerImpl
        webSocketServer = new WebSocketServerImpl();
        webSocketServer.setPort(port);
        webSocketServer.setWebsocketPath(websocketPath);
        webSocketServer.setSessionManager(sessionManager);
        webSocketServer.setServerMessageHandler(serverMessageHandler);
        webSocketServer.start(port);
    }

    public void stop() {
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
    }
} 