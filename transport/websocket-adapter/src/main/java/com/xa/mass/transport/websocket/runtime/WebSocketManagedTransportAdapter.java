package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Managed lifecycle wrapper for the embedded WebSocket adapter runtime.
 */
public final class WebSocketManagedTransportAdapter implements ManagedTransportAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketManagedTransportAdapter.class);

    private final int maxConnections;
    private final WebSocketDispatchRuntimeContext dispatcherContext;
    private boolean running = false;

    public WebSocketManagedTransportAdapter(int maxConnections, WebSocketDispatchRuntimeContext dispatcherContext) {
        this.maxConnections = maxConnections;
        this.dispatcherContext = dispatcherContext;
    }

    @Override
    public void start() {
        MDC.clear();
        logger.info("Starting WebSocket managed transport adapter with max connections: {}", maxConnections);

        try {
            initializeEndpointRuntime();

            running = true;
            logger.info("WebSocket managed transport adapter started successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Failed to start WebSocket managed transport adapter", e);
            throw new RuntimeException("Failed to start WebSocket managed transport adapter", e);
        }
    }

    @Override
    public void stop() {
        MDC.clear();
        if (!running) {
            logger.info("WebSocket managed transport adapter is not running, skipping stop");
            return;
        }

        logger.info("Stopping WebSocket managed transport adapter...");

        try {
            shutdownEndpointRuntime();

            running = false;
            logger.info("WebSocket managed transport adapter stopped successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Error stopping WebSocket managed transport adapter", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void initializeEndpointRuntime() {
        logger.info("Initializing endpoint runtime...");
        logger.info("Endpoint runtime ready (max={} connections)", maxConnections);
    }

    private void shutdownEndpointRuntime() {
        logger.info("Shutting down endpoint runtime...");
        try {
            WorkerEndpointRegistry endpointRegistry = dispatcherContext.getEndpointRegistry();
            if (endpointRegistry != null) {
                endpointRegistry.shutdown();
            }
            logger.info("Endpoint runtime shut down");
        } catch (Exception e) {
            logger.error("Error shutting down endpoint runtime", e);
        }
    }
}
