package com.xa.mass.starter.transport;

import com.xa.mass.gateway.server.WebSocketServerImpl;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;

/**
 * Current default inbound transport-server factory backed by Netty WebSocket.
 */
public class WebSocketTransportServerFactory implements TransportServerFactory<TransportServerFactoryContext> {

    @Override
    public TransportServer create(TransportServerFactoryContext context) {
        WebSocketServerImpl transportServer = new WebSocketServerImpl();
        transportServer.setPort(context.getPort());
        transportServer.setWebsocketPath(context.getEndpointPath());
        transportServer.setDispatcherContext(context.getDispatcherContext());
        if (context.getWorkerEndpointRegistry() instanceof ServerSessionManager sessionManager) {
            transportServer.setSessionManager(sessionManager);
        }
        return transportServer;
    }
}
