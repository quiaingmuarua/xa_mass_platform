package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.transport.TransportServer;

public class MassServerStater {
    private final MassServerConfig config;
    private TransportServer transportServer;
    private DispatchRuntimeContext dispatcherContext;


    public MassServerStater(MassServerConfig config) {
        this.config = config;
        this.dispatcherContext = config.getDispatcherContext();
    }

    public void start() {
        WebSocketServerImpl webSocketServer = new WebSocketServerImpl();
        webSocketServer.setPort(config.getPort());
        webSocketServer.setWebsocketPath(config.getWebsocketPath());
        webSocketServer.setDispatcherContext(dispatcherContext);
        transportServer = webSocketServer;
        try {
            transportServer.start(config.getPort());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start transport server", e);
        }
    }

    public void stop() {
        if (transportServer != null) {
            try {
                transportServer.stop();
            } catch (Exception e) {
                throw new RuntimeException("Failed to stop transport server", e);
            }
        }
    }

    public boolean isRunning() {
        return transportServer != null && transportServer.isRunning();

    }
}
