package com.xa.mass.transport;

/**
 * Transport-neutral inbound server contract.
 *
 * <p>Current mainline implementations may be WebSocket-backed, but the
 * scheduling/runtime layer should depend only on this contract so additional
 * transports such as gRPC or custom socket servers can be introduced without
 * changing engine semantics.
 */
public interface TransportServer {

    void start() throws Exception;

    void stop() throws Exception;

    boolean isRunning();
}
