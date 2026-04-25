package com.xa.mass.starter;

import com.xa.mass.transport.websocket.dispatcher.WebSocketMessageDispatcher;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.starter.config.WebSocketConfig;
import com.xa.mass.transport.WorkerEndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Legacy WebSocket adapter runtime escape hatch.
 *
 * <p>Embedded-runtime mainline now owns WebSocket adapter lifecycle through
 * adapter bootstrap/contribution assembly. Keep this class only as a
 * compatibility shell for advanced embedders that still construct and control
 * the adapter directly.
 */
@Deprecated(forRemoval = false)
public class MassWebSocketAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MassWebSocketAdapter.class);

    private final WebSocketConfig config;
    private final WebSocketDispatchRuntimeContext dispatcherContext;
    private WebSocketMessageDispatcher messageDispatcher;
    private boolean running = false;

    public MassWebSocketAdapter(WebSocketConfig config, WebSocketDispatchRuntimeContext dispatcherContext) {
        this.config = config;
        this.dispatcherContext = dispatcherContext;
    }

    public void start() {
        MDC.clear();
        if (!config.isEnabled()) {
            logger.info("MassWebSocketAdapter is disabled, skipping start");
            return;
        }

        logger.info("Starting MassWebSocketAdapter with max connections: {}", config.getMaxConnections());

        try {
            startDispatcher();
            initializeEndpointRuntime();

            running = true;
            logger.info("MassWebSocketAdapter started successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Failed to start MassWebSocketAdapter", e);
            throw new RuntimeException("Failed to start MassWebSocketAdapter", e);
        }
    }

    public void stop() {
        MDC.clear();
        if (!running) {
            logger.info("MassWebSocketAdapter is not running, skipping stop");
            return;
        }

        logger.info("Stopping MassWebSocketAdapter...");

        try {
            stopDispatcher();
            shutdownEndpointRuntime();

            running = false;
            logger.info("MassWebSocketAdapter stopped successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Error stopping MassWebSocketAdapter", e);
        }
    }

    private void startDispatcher() {
        logger.info("Starting WebSocket dispatcher...");

        try {
            messageDispatcher = new WebSocketMessageDispatcher(dispatcherContext);
            messageDispatcher.start();
            logger.info("WebSocket dispatcher started successfully");
        } catch (Exception e) {
            logger.error("Failed to start WebSocket dispatcher", e);
            throw new RuntimeException("Failed to start WebSocket dispatcher", e);
        }
    }

    private void stopDispatcher() {
        logger.info("Stopping WebSocket dispatcher...");

        try {
            if (messageDispatcher != null) {
                messageDispatcher.stop();
                logger.info("WebSocket dispatcher stopped successfully");
            }
        } catch (Exception e) {
            logger.error("Error stopping WebSocket dispatcher", e);
        }
    }

    private void initializeEndpointRuntime() {
        logger.info("Initializing endpoint runtime...");
        logger.info("Endpoint runtime ready (max={} connections)", config.getMaxConnections());
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

    public boolean isRunning() {
        return running && messageDispatcher != null && messageDispatcher.isRunning();
    }

    /**
     * @deprecated The WebSocket config object is an advanced embedding detail.
     * Default embedding should configure WebSocket behavior before runtime
     * assembly rather than reading live adapter runtime state back through
     * {@code MassWebSocketAdapter}.
     */
    @Deprecated(forRemoval = false)
    public WebSocketConfig getWebSocketConfig() {
        return config;
    }

    /**
     * @deprecated The WebSocket dispatcher is an internal adapter implementation
     * detail. Advanced embedding should use only {@link #start()},
     * {@link #stop()}, and {@link #isRunning()} rather than reaching into the
     * dispatcher runtime.
     */
    @Deprecated(forRemoval = false)
    public WebSocketMessageDispatcher getWebSocketMessageDispatcher() {
        return messageDispatcher;
    }
}
