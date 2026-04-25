package com.xa.mass.gateway.server;

import com.xa.mass.transport.TransportServer;
import io.netty.channel.Channel;

/**
 * WebSocket-specific server escape hatch retained for adapter-local diagnostics.
 *
 * <p>Runtime composition should prefer the transport-neutral {@link TransportServer}
 * contract whenever possible.
 */
public interface MassWebSocketServer extends TransportServer {
    /**
     * WebSocket-only escape hatch retained for current adapter-specific tests and diagnostics.
     */
    Channel getClientChannel(String clientId);
}
