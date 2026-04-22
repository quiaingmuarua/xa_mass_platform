package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.starter.config.GatewayConfig;
import com.xa.mass.transport.WorkerEndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Gateway runtime component.
 * Handles external connections, message routing, and message processing.
 */
public class MassGateway {

    private static final Logger logger = LoggerFactory.getLogger(MassGateway.class);

    private final GatewayConfig config;
    private final DispatchRuntimeContext dispatcherContext;
    private ServerMessageDispatcher messageDispatcher;
    private boolean running = false;

    public MassGateway(GatewayConfig config, DispatchRuntimeContext dispatcherContext) {
        this.config = config;
        this.dispatcherContext = dispatcherContext;
    }

    public void start() {
        MDC.clear();
        if (!config.isEnabled()) {
            logger.info("MassGateway is disabled, skipping start");
            return;
        }

        logger.info("Starting MassGateway with max connections: {}", config.getMaxConnections());

        try {
            startMessageEngine();
            initializeConnectionManagement();

            running = true;
            logger.info("MassGateway started successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Failed to start MassGateway", e);
            throw new RuntimeException("Failed to start MassGateway", e);
        }
    }

    public void stop() {
        MDC.clear();
        if (!running) {
            logger.info("MassGateway is not running, skipping stop");
            return;
        }

        logger.info("Stopping MassGateway...");

        try {
            stopMessageEngine();
            shutdownConnectionManagement();

            running = false;
            logger.info("MassGateway stopped successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Error stopping MassGateway", e);
        }
    }

    private void startMessageEngine() {
        logger.info("Starting message processing engine...");

        try {
            messageDispatcher = new ServerMessageDispatcher(dispatcherContext);
            messageDispatcher.start();
            logger.info("Message processing engine started successfully");
        } catch (Exception e) {
            logger.error("Failed to start message processing engine", e);
            throw new RuntimeException("Failed to start message processing engine", e);
        }
    }

    private void stopMessageEngine() {
        logger.info("Stopping message processing engine...");

        try {
            if (messageDispatcher != null) {
                messageDispatcher.stop();
                logger.info("Message processing engine stopped successfully");
            }
        } catch (Exception e) {
            logger.error("Error stopping message processing engine", e);
        }
    }

    private void initializeConnectionManagement() {
        logger.info("Initializing connection management...");
        // ServerSessionManager.INSTANCE is a self-initializing singleton; no explicit setup needed.
        logger.info("Connection management ready (max={} connections)", config.getMaxConnections());
    }

    private void shutdownConnectionManagement() {
        logger.info("Shutting down connection management...");
        try {
            WorkerEndpointRegistry endpointRegistry = dispatcherContext.getSessionManager();
            if (endpointRegistry != null) {
                endpointRegistry.shutdown();
            }
            logger.info("Connection management shut down");
        } catch (Exception e) {
            logger.error("Error shutting down connection management", e);
        }
    }

    public boolean isRunning() {
        return running && messageDispatcher != null && messageDispatcher.isRunning();
    }

    public GatewayConfig getConfig() {
        return config;
    }

    public ServerMessageDispatcher getMessageDispatcher() {
        return messageDispatcher;
    }

    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }
}
