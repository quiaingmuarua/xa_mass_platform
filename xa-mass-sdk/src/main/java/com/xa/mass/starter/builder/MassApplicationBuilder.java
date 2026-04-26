package com.xa.mass.starter.builder;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds {@link MassApplication} instances from transport and engine configuration.
 */
public class MassApplicationBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MassApplicationBuilder.class);
    private static final String API_MODE_UNSUPPORTED_MESSAGE =
            "API-based transport is not implemented yet. Use queue/polling transport or provide a real transport adapter.";

    private TransportConfig transportConfig = new TransportConfig();
    private EngineConfig engineConfig = new EngineConfig();

    private MassApplicationBuilder() {
    }

    public static MassApplicationBuilder create() {
        return new MassApplicationBuilder();
    }

    /**
     * Creates a development runtime with auto-provisioned in-memory queues.
     * Suitable for local development and integration tests.
     */
    public static MassApplication createDevelopment(int port) {
        return create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(port)
                                .enabled(true)
                                .maxConnections(1000))
                        .inputQueue(new InMemoryMessageQueue<>("input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", WorkerTransportMessage.class)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(8))
                .build();
    }

    /**
     * @deprecated Use {@link #createDevelopment(int)} — queues are now provisioned internally.
     * Pass custom queues via {@link MassApplicationBuilder#create()} and the {@code transport()} builder
     * if you need to share queue instances across components.
     */
    @Deprecated(forRemoval = false)
    public static MassApplication createDevelopment(int port, MessageQueue<String> inputQueue, MessageQueue<WorkerTransportMessage> outputQueue) {
        return create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(port)
                                .enabled(true)
                                .maxConnections(1000))
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(8))
                .build();
    }

    /**
     * Creates a production runtime with auto-provisioned in-memory queues.
     */
    public static MassApplication createProduction(int port) {
        return create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(port)
                                .enabled(true)
                                .maxConnections(5000))
                        .inputQueue(new InMemoryMessageQueue<>("input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", WorkerTransportMessage.class)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(16))
                .build();
    }

    /**
     * @deprecated Use {@link #createProduction(int)} — queues are now provisioned internally.
     */
    @Deprecated(forRemoval = false)
    public static MassApplication createProduction(int port, MessageQueue<String> inputQueue, MessageQueue<WorkerTransportMessage> outputQueue) {
        return create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(port)
                                .enabled(true)
                                .maxConnections(5000))
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(16))
                .build();
    }

    /**
     * @deprecated API-based transport is not implemented and now fails fast.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    public static MassApplication createApiMode(int port, String inputApiUrl, String outputApiUrl, String apiKey) {
        throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
    }

    public static MassApplication createTest(int port) {
        return create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(port)
                                .enabled(true)
                                .maxConnections(100)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(2))
                .build();
    }

    public MassApplicationBuilder server(int port) {
        return transportServer(port, "/ws");
    }

    /**
     * @deprecated Prefer {@link #transportServer(int, String)} so callers do not
     * encode WebSocket vocabulary into stable SDK/server boot code.
     */
    @Deprecated(forRemoval = false)
    public MassApplicationBuilder server(int port, String transportEndpointPath) {
        return transportServer(port, transportEndpointPath);
    }

    /**
     * @deprecated Prefer {@link #transport(Consumer)} with
     * {@code webSocketAdapter(...)} so adapter-owned server settings stay on the
     * concrete adapter rather than transport-global compatibility helpers.
     */
    @Deprecated(forRemoval = false)
    public MassApplicationBuilder transportServer(int port) {
        return transportServer(port, "/ws");
    }

    /**
     * @deprecated Prefer {@link #transport(Consumer)} with
     * {@code webSocketAdapter(...)} so adapter-owned server settings stay on the
     * concrete adapter rather than transport-global compatibility helpers.
     */
    @Deprecated(forRemoval = false)
    public MassApplicationBuilder transportServer(int port, String transportEndpointPath) {
        this.transportConfig.getDefaultWebSocketAdapterConfig().setServerPort(port);
        this.transportConfig.getDefaultWebSocketAdapterConfig().setEndpointPath(transportEndpointPath);
        return this;
    }

    public MassApplicationBuilder transport(Consumer<TransportBuilder> transportConfigurator) {
        TransportBuilder transportBuilder = new TransportBuilder(transportConfig);
        transportConfigurator.accept(transportBuilder);
        return this;
    }

    /**
     * @deprecated Prefer {@link #transport(Consumer)} so SDK/runtime assembly
     * is described in transport-neutral terms rather than a WebSocket-specific
     * entrypoint name.
     */
    @Deprecated(forRemoval = false)
    public MassApplicationBuilder websocket(Consumer<WebSocketBuilder> websocketConfigurator) {
        WebSocketBuilder webSocketBuilder = new WebSocketBuilder(transportConfig);
        websocketConfigurator.accept(webSocketBuilder);
        return this;
    }

    public MassApplicationBuilder engine(Consumer<EngineBuilder> engineConfigurator) {
        EngineBuilder engineBuilder = new EngineBuilder(engineConfig);
        engineConfigurator.accept(engineBuilder);
        return this;
    }

    public MassApplication build() {
        TransportConfig transportSnapshot = new TransportConfig(transportConfig);
        EngineConfig engineSnapshot = new EngineConfig(engineConfig);
        logger.info("Building MassApplication with configuration: adapters={}, transport={}, engine={}",
                describeAdapterSummary(transportSnapshot),
                transportSnapshot.isEnabled(),
                engineSnapshot.isEnabled());

        MassEngine engine = null;
        if (engineSnapshot.isEnabled()) {
            engine = new MassEngine(engineSnapshot);
            logger.info("MassEngine built");
        } else {
            logger.info("MassEngine is disabled, skipping build");
        }

        return new MassApplication(
                engine,
                transportSnapshot,
                engineSnapshot
        );
    }

    private static String describeAdapterSummary(TransportConfig transportConfig) {
        WebSocketAdapterConfig webSocket = transportConfig.getDefaultWebSocketAdapterConfig();
        SocketAdapterConfig socket = transportConfig.getDefaultSocketAdapterConfig();
        return "[websocket(enabled=" + webSocket.isEnabled()
                + ",serverEnabled=" + webSocket.isServerEnabled()
                + ",port=" + webSocket.getServerPort()
                + ",path=" + webSocket.getEndpointPath()
                + "), socket(enabled=" + socket.isEnabled()
                + ",serverEnabled=" + socket.isServerEnabled()
                + ",host=" + socket.getBindHost()
                + ",port=" + socket.getServerPort()
                + ")]";
    }

    public static class TransportBuilder {
        protected final TransportConfig config;

        public TransportBuilder(TransportConfig config) {
            this.config = config;
        }

        /**
         * @deprecated Prefer explicit adapter toggles such as
         * {@code webSocketAdapter(...)} or {@code socketAdapter(...)}. This
         * transport-global helper mutates only the bundled default WebSocket
         * adapter and is compatibility-only.
         */
        @Deprecated(forRemoval = false)
        public TransportBuilder enabled(boolean enabled) {
            config.getDefaultWebSocketAdapterConfig().setEnabled(enabled);
            return this;
        }

        public TransportBuilder webSocketAdapter(Consumer<WebSocketAdapterBuilder> webSocketAdapterConfigurator) {
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(config.getDefaultWebSocketAdapterConfig());
            webSocketAdapterConfigurator.accept(builder);
            return this;
        }

        public TransportBuilder socketAdapter(Consumer<SocketAdapterBuilder> socketAdapterConfigurator) {
            SocketAdapterBuilder builder = new SocketAdapterBuilder(config.getDefaultSocketAdapterConfig());
            socketAdapterConfigurator.accept(builder);
            return this;
        }

        /**
         * @deprecated Prefer {@code webSocketAdapter(...).serverEnabled(...)} so
         * adapter-owned server settings stay on the concrete adapter rather than
         * a transport-global compatibility helper.
         */
        @Deprecated(forRemoval = false)
        public TransportBuilder transportServerEnabled(boolean enabled) {
            config.getDefaultWebSocketAdapterConfig().setServerEnabled(enabled);
            return this;
        }

        /**
         * @deprecated Prefer {@code webSocketAdapter(...).endpointPath(...)} so
         * adapter-owned server settings stay on the concrete adapter rather than
         * a transport-global compatibility helper.
         */
        @Deprecated(forRemoval = false)
        public TransportBuilder transportEndpointPath(String transportEndpointPath) {
            config.getDefaultWebSocketAdapterConfig().setEndpointPath(transportEndpointPath);
            return this;
        }

        /**
         * @deprecated Prefer
         * {@code webSocketAdapter(...).transportServerFactory(...)} so adapter
         * bootstrap overrides stay attached to the concrete adapter rather than a
         * transport-global compatibility helper.
         */
        @Deprecated(forRemoval = false)
        public TransportBuilder transportServerFactory(
                TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
            config.getDefaultWebSocketAdapterConfig().setTransportServerFactory(transportServerFactory);
            return this;
        }

        public TransportBuilder workerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
            config.setWorkerTransportRuntimeFactory(workerTransportRuntimeFactory);
            return this;
        }

        /**
         * @deprecated Prefer adapter-owned configuration such as
         * {@code webSocketAdapter(...).maxConnections(...)} or
         * {@code socketAdapter(...).maxConnections(...)}. This transport-global
         * helper mutates only the bundled default WebSocket adapter and is
         * compatibility-only.
         */
        @Deprecated(forRemoval = false)
        public TransportBuilder maxConnections(int maxConnections) {
            config.getDefaultWebSocketAdapterConfig().setMaxConnections(maxConnections);
            return this;
        }

        public TransportBuilder inputQueue(MessageQueue<String> inputQueue) {
            config.setInputQueue(inputQueue);
            return this;
        }

        public TransportBuilder outputQueue(MessageQueue<WorkerTransportMessage> outputQueue) {
            config.setOutputQueue(outputQueue);
            return this;
        }

        public TransportBuilder addTransportAdapterBootstrap(
                TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
            config.addTransportAdapterBootstrap(transportAdapterBootstrap);
            return this;
        }

        /**
         * @deprecated API-based transport is not implemented and now fails fast.
         */
        @Deprecated(since = "2.0.0", forRemoval = false)
        public TransportBuilder apiMode(String inputApiUrl, String outputApiUrl, String apiKey) {
            throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
        }

        public TransportBuilder queueMode() {
            config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
            return this;
        }

        /**
         * Overrides the default worker system-event channel. Useful for custom
         * transport adapters or testing with a mock channel.
         */
        public TransportBuilder systemEventChannel(WorkerSystemEventChannel channel) {
            config.setCustomSystemEventChannel(channel);
            return this;
        }
    }

    public static class WebSocketAdapterBuilder {
        private final WebSocketAdapterConfig config;

        public WebSocketAdapterBuilder(WebSocketAdapterConfig config) {
            this.config = config;
        }

        public WebSocketAdapterBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder serverEnabled(boolean enabled) {
            config.setServerEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder server(int port) {
            return server(port, "/ws");
        }

        public WebSocketAdapterBuilder server(int port, String endpointPath) {
            config.setServerPort(port);
            config.setEndpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder endpointPath(String endpointPath) {
            config.setEndpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder maxConnections(int maxConnections) {
            config.setMaxConnections(maxConnections);
            return this;
        }

        public WebSocketAdapterBuilder transportServerFactory(
                TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
            config.setTransportServerFactory(transportServerFactory);
            return this;
        }
    }

    public static class SocketAdapterBuilder {
        private final SocketAdapterConfig config;

        public SocketAdapterBuilder(SocketAdapterConfig config) {
            this.config = config;
        }

        public SocketAdapterBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public SocketAdapterBuilder serverEnabled(boolean enabled) {
            config.setServerEnabled(enabled);
            return this;
        }

        public SocketAdapterBuilder server(int port) {
            config.setServerPort(port);
            return this;
        }

        public SocketAdapterBuilder maxConnections(int maxConnections) {
            config.setMaxConnections(maxConnections);
            return this;
        }
    }

    /**
     * @deprecated Prefer {@link TransportBuilder}; WebSocket is one adapter,
     * not the primary transport-composition naming boundary.
     */
    @Deprecated(forRemoval = false)
    public static class WebSocketBuilder extends TransportBuilder {

        public WebSocketBuilder(TransportConfig config) {
            super(config);
        }
    }

    public static class EngineBuilder {
        private final EngineConfig config;

        public EngineBuilder(EngineConfig config) {
            this.config = config;
        }

        public EngineBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public EngineBuilder workerThreads(int workerThreads) {
            config.setWorkerThreads(workerThreads);
            return this;
        }

        public EngineBuilder assignmentRetryDelayMillis(long assignmentRetryDelayMillis) {
            config.setAssignmentRetryDelayMillis(assignmentRetryDelayMillis);
            return this;
        }

        public EngineBuilder leaseWatchdogIntervalSeconds(long leaseWatchdogIntervalSeconds) {
            config.setLeaseWatchdogIntervalSeconds(leaseWatchdogIntervalSeconds);
            return this;
        }

        public EngineBuilder taskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
            config.setTaskMessageLeaseSeconds(taskMessageLeaseSeconds);
            return this;
        }

        public EngineBuilder bootstrapDataProvider(MassBootstrapDataProvider bootstrapDataProvider) {
            config.setBootstrapDataProvider(bootstrapDataProvider);
            return this;
        }

        public EngineBuilder scheduler(TaskScheduler scheduler) {
            config.setScheduler(scheduler);
            return this;
        }

        public EngineBuilder taskManager(TaskManager taskManager) {
            config.setTaskManager(taskManager);
            return this;
        }

        public EngineBuilder workerManager(WorkerManager workerManager) {
            config.setWorkerManager(workerManager);
            return this;
        }

        public EngineBuilder ruleManager(RuleManager<Map<String, Object>> ruleManager) {
            config.setRuleManager(ruleManager);
            return this;
        }

    }
}
