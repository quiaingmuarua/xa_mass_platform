package com.xa.mass.gateway.server;

import com.xa.mass.transport.TransportServer;
import io.netty.channel.Channel;

/**
 * Interface for WebSocket server functionality
 */
public interface MassWebSocketServer extends TransportServer {
    /**
     * Start the WebSocket server
     * @param port The port to start the server on
     * @throws Exception if server fails to start
     */
    /**
     * WebSocket-only escape hatch retained for current adapter-specific tests and diagnostics.
     *
     * <p>Runtime composition should prefer the transport-neutral {@link TransportServer} contract.
     */
    Channel getClientChannel(String clientId);
}
