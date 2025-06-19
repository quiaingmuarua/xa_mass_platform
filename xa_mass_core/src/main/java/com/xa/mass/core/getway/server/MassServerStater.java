package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.dispatcher.DispatcherContext;

public class MassServerStater {
    private final MassServerConfig config;
    private WebSocketServerImpl webSocketServer;

    public MassServerStater(MassServerConfig config) {
        this.config = config;
    }

    public void start() {
        webSocketServer = new WebSocketServerImpl();
        webSocketServer.setPort(config.getPort());
        webSocketServer.setWebsocketPath(config.getWebsocketPath());
        webSocketServer.setDispatcherContext(config.getDispatcherContext());
        webSocketServer.start(config.getPort());
    }

    public void stop() {
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
    }
} 