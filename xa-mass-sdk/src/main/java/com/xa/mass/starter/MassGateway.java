package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.ServerMessageDispatcher;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.starter.config.GatewayConfig;
import com.xa.mass.transport.WorkerEndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Gateway adapter runtime.
 *
 * <p>Owns dispatcher lifecycle plus transport endpoint shutdown for the
 * current gateway adapter. It is not a business-event router.
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
            startDispatcher();
            initializeEndpointRuntime();

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
            stopDispatcher();
            shutdownEndpointRuntime();

            running = false;
            logger.info("MassGateway stopped successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Error stopping MassGateway", e);
        }
    }

    private void startDispatcher() {
        logger.info("Starting gateway dispatcher...");

        try {
            messageDispatcher = new ServerMessageDispatcher(dispatcherContext);
            messageDispatcher.start();
            logger.info("Gateway dispatcher started successfully");
        } catch (Exception e) {
            logger.error("Failed to start gateway dispatcher", e);
            throw new RuntimeException("Failed to start gateway dispatcher", e);
        }
    }

    private void stopDispatcher() {
        logger.info("Stopping gateway dispatcher...");

        try {
            if (messageDispatcher != null) {
                messageDispatcher.stop();
                logger.info("Gateway dispatcher stopped successfully");
            }
        } catch (Exception e) {
            logger.error("Error stopping gateway dispatcher", e);
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

    public GatewayConfig getConfig() {
        return config;
    }

    public ServerMessageDispatcher getMessageDispatcher() {
        return messageDispatcher;
    }
}
