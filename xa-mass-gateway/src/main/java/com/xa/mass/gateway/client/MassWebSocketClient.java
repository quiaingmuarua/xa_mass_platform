package com.xa.mass.gateway.client;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Interface for WebSocket client functionality
 */
public interface MassWebSocketClient {
    /**
     * Connect to a WebSocket server
     * @param serverUri The URI of the WebSocket server
     * @throws Exception if connection fails
     */
    void connect(URI serverUri) throws Exception;

    /**
     * Disconnect from the WebSocket server
     * @throws Exception if disconnection fails
     */
    void disconnect() throws Exception;

    /**
     * Check if the client is connected
     * @return true if connected, false otherwise
     */
    boolean isConnected();

    /**
     * Send a message to the server
     * @param message The message to send
     * @throws Exception if sending fails
     */
    void sendMessage(String message) throws Exception;



     boolean connectBlocking(long timeout, TimeUnit timeUnit) throws InterruptedException;

} 