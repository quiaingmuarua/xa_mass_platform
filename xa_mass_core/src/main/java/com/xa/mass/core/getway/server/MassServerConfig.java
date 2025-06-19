package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.dispatcher.DispatcherContext;

public class MassServerConfig {
    private final int port;
    private final String websocketPath;
    private final DispatcherContext dispatcherContext;

    public MassServerConfig(
            int port,
            String websocketPath,
            DispatcherContext dispatcherContext
    ) {
        this.port = port;
        this.websocketPath = websocketPath;
        this.dispatcherContext = dispatcherContext;
    }

    public int getPort() { return port; }
    public String getWebsocketPath() { return websocketPath; }
    public DispatcherContext getDispatcherContext() { return dispatcherContext; }
} 