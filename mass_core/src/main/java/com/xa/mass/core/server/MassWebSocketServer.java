package com.xa.mass.core.server;

import io.netty.channel.Channel;

/**
 * Interface for WebSocket server functionality
 */
public interface MassWebSocketServer {
    /**
     * Start the WebSocket server
     * @param port The port to start the server on
     * @throws Exception if server fails to start
     */
    void start(int port) throws Exception;

    /**
     * Stop the WebSocket server
     * @throws Exception if server fails to stop
     */
    void stop() throws Exception;

    /**
     * Check if the server is running
     * @return true if server is running, false otherwise
     */
    boolean isRunning();

    /**
     * Get the channel for a specific client
     * @param clientId The client identifier
     * @return The channel for the client, or null if not found
     */
    Channel getClientChannel(String clientId);
} 