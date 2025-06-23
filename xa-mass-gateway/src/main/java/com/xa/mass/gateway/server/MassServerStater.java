package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;

public class MassServerStater {
    private final MassServerConfig config;
    private WebSocketServerImpl webSocketServer;
    private DispatchRuntimeContext dispatcherContext;


    public MassServerStater(MassServerConfig config) {
        this.config = config;
        this.dispatcherContext = config.getDispatcherContext();
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

    public boolean isRunning() {
        return webSocketServer != null && webSocketServer.isRunning();

    }
}