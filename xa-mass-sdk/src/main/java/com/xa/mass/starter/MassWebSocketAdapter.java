package com.xa.mass.starter;

import com.xa.mass.transport.websocket.dispatcher.WebSocketMessageDispatcher;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.starter.config.WebSocketConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketManagedTransportAdapter;
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

    private final WebSocketAdapterConfig adapterConfig;
    private final WebSocketDispatchRuntimeContext dispatcherContext;
    private final WebSocketManagedTransportAdapter managedTransportAdapter;
    private WebSocketMessageDispatcher messageDispatcher;

    /**
     * @deprecated Prefer the adapter-owned
     * {@link #MassWebSocketAdapter(WebSocketAdapterConfig, WebSocketDispatchRuntimeContext)}
     * overload. This compatibility constructor snapshots only the bundled
     * WebSocket adapter config from the legacy transport-global config shell.
     */
    @Deprecated(forRemoval = false)
    public MassWebSocketAdapter(WebSocketConfig config, WebSocketDispatchRuntimeContext dispatcherContext) {
        this(config != null ? config.getDefaultWebSocketAdapterConfig() : null, dispatcherContext);
    }

    public MassWebSocketAdapter(WebSocketAdapterConfig adapterConfig,
                                WebSocketDispatchRuntimeContext dispatcherContext) {
        this.adapterConfig = new WebSocketAdapterConfig(java.util.Objects.requireNonNull(adapterConfig, "adapterConfig"));
        this.dispatcherContext = java.util.Objects.requireNonNull(dispatcherContext, "dispatcherContext");
        this.managedTransportAdapter = new WebSocketManagedTransportAdapter(
                this.adapterConfig.getMaxConnections(),
                this.dispatcherContext
        );
    }

    public void start() {
        MDC.clear();
        if (!adapterConfig.isEnabled()) {
            logger.info("MassWebSocketAdapter is disabled, skipping start");
            return;
        }

        logger.info("Starting MassWebSocketAdapter with max connections: {}", adapterConfig.getMaxConnections());

        try {
            dispatcher().start();
            managedTransportAdapter.start();
            logger.info("MassWebSocketAdapter started successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Failed to start MassWebSocketAdapter", e);
            throw new RuntimeException("Failed to start MassWebSocketAdapter", e);
        }
    }

    public void stop() {
        MDC.clear();
        if (!isRunning()) {
            logger.info("MassWebSocketAdapter is not running, skipping stop");
            return;
        }

        logger.info("Stopping MassWebSocketAdapter...");

        try {
            stopDispatcher();
            managedTransportAdapter.stop();
            logger.info("MassWebSocketAdapter stopped successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Error stopping MassWebSocketAdapter", e);
        }
    }

    private WebSocketMessageDispatcher dispatcher() {
        if (messageDispatcher == null) {
            logger.info("Creating WebSocket compatibility dispatcher shell...");
            messageDispatcher = new WebSocketMessageDispatcher(dispatcherContext);
        }
        return messageDispatcher;
    }

    private void stopDispatcher() {
        logger.info("Stopping WebSocket compatibility dispatcher shell...");

        try {
            if (messageDispatcher != null) {
                messageDispatcher.stop();
                logger.info("WebSocket compatibility dispatcher shell stopped successfully");
            }
        } catch (Exception e) {
            logger.error("Error stopping WebSocket compatibility dispatcher shell", e);
        }
    }

    public boolean isRunning() {
        return managedTransportAdapter.isRunning() && messageDispatcher != null && messageDispatcher.isRunning();
    }

    /**
     * @deprecated The WebSocket config object is an advanced embedding detail.
     * Default embedding should configure WebSocket behavior before runtime
     * assembly rather than reading live adapter runtime state back through
     * {@code MassWebSocketAdapter}. This compatibility accessor returns a
     * snapshot copy rather than the live transport config object.
     */
    @Deprecated(forRemoval = false)
    public WebSocketConfig getWebSocketConfig() {
        WebSocketConfig config = new WebSocketConfig();
        config.setDefaultWebSocketAdapterConfig(adapterConfig);
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
