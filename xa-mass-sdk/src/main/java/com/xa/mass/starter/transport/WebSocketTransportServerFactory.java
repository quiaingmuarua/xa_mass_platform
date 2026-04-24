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
        if (!(context.getWorkerEndpointRegistry() instanceof ServerSessionManager sessionManager)) {
            throw new IllegalStateException("WebSocket transport requires ServerSessionManager endpoint registry");
        }
        return new WebSocketServerImpl(context.getEndpointPath(), context.getDispatcherContext(), sessionManager);
    }
}
