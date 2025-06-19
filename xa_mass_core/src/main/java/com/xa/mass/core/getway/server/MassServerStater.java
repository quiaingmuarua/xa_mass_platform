package com.xa.mass.core.getway.server;


import com.xa.mass.core.getway.dispatcher.DispatcherContext;

public class MassServerStater {
    private final MassServerConfig config;
    private WebSocketServerImpl webSocketServer;
    private DispatcherContext dispatcherContext;


    public MassServerStater(MassServerConfig config, DispatcherContext dispatcherContext) {
        this.config = config;
        this.dispatcherContext = dispatcherContext;
    }

    public void start() {
        webSocketServer = new WebSocketServerImpl();
        webSocketServer.setPort(config.getPort());
        webSocketServer.setWebsocketPath(config.getWebsocketPath());
        webSocketServer.setDispatcherContext(dispatcherContext);
        webSocketServer.start(config.getPort());
    }

    public void stop() {
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
    }
} 