package com.xa.mass.starter.builder;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WebSocketTransportServerFactory;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds {@link MassApplication} instances from gateway and engine configuration.
 */
public class MassApplicationBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MassApplicationBuilder.class);

    private int serverPort = 8080;
    private GatewayConfig gatewayConfig = new GatewayConfig();
    private EngineConfig engineConfig = new EngineConfig();

    private MassApplicationBuilder() {
        this.gatewayConfig.setTransportServerFactory(new WebSocketTransportServerFactory());
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
                .server(port)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(1000)
                        .inputQueue(new InMemoryMessageQueue<>("input", Envelope.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", Envelope.class)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(8))
                .build();
    }

    /**
     * @deprecated Use {@link #createDevelopment(int)} — queues are now provisioned internally.
     * Pass custom queues via {@link MassApplicationBuilder#create()} and the {@code gateway()} builder
     * if you need to share queue instances across components.
     */
    @Deprecated(forRemoval = false)
    public static MassApplication createDevelopment(int port, MessageQueue<Envelope> inputQueue, MessageQueue<Envelope> outputQueue) {
        return create()
                .server(port)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(1000)
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
                .server(port)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(5000)
                        .inputQueue(new InMemoryMessageQueue<>("input", Envelope.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", Envelope.class)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(16))
                .build();
    }

    /**
     * @deprecated Use {@link #createProduction(int)} — queues are now provisioned internally.
     */
    @Deprecated(forRemoval = false)
    public static MassApplication createProduction(int port, MessageQueue<Envelope> inputQueue, MessageQueue<Envelope> outputQueue) {
        return create()
                .server(port)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(5000)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(16))
                .build();
    }

    public static MassApplication createApiMode(int port, String inputApiUrl, String outputApiUrl, String apiKey) {
        return create()
                .server(port)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(1000)
                        .apiMode(inputApiUrl, outputApiUrl, apiKey))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(8))
                .build();
    }

    public static MassApplication createTest(int port) {
        return create()
                .server(port)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(100))
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

    public MassApplicationBuilder transportServer(int port) {
        return transportServer(port, "/ws");
    }

    public MassApplicationBuilder transportServer(int port, String transportEndpointPath) {
        this.serverPort = port;
        this.gatewayConfig.setTransportEndpointPath(transportEndpointPath);
        return this;
    }

    public MassApplicationBuilder gateway(Consumer<GatewayBuilder> gatewayConfigurator) {
        GatewayBuilder gatewayBuilder = new GatewayBuilder(gatewayConfig);
        gatewayConfigurator.accept(gatewayBuilder);
        return this;
    }

    public MassApplicationBuilder engine(Consumer<EngineBuilder> engineConfigurator) {
        EngineBuilder engineBuilder = new EngineBuilder(engineConfig);
        engineConfigurator.accept(engineBuilder);
        return this;
    }

    public MassApplication build() {
        GatewayConfig gatewaySnapshot = new GatewayConfig(gatewayConfig);
        EngineConfig engineSnapshot = new EngineConfig(engineConfig);
        logger.info("Building MassApplication with configuration: port={}, gateway={}, engine={}",
                serverPort,
                gatewaySnapshot.isEnabled(),
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
                serverPort,
                gatewaySnapshot.getTransportEndpointPath(),
                gatewaySnapshot,
                engineSnapshot
        );
    }

    public static class GatewayBuilder {
        private final GatewayConfig config;

        public GatewayBuilder(GatewayConfig config) {
            this.config = config;
        }

        public GatewayBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public GatewayBuilder transportServerEnabled(boolean enabled) {
            config.setTransportServerEnabled(enabled);
            return this;
        }

        public GatewayBuilder transportEndpointPath(String transportEndpointPath) {
            config.setTransportEndpointPath(transportEndpointPath);
            return this;
        }

        public GatewayBuilder transportServerFactory(
                TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
            config.setTransportServerFactory(transportServerFactory);
            return this;
        }

        public GatewayBuilder workerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
            config.setWorkerTransportRuntimeFactory(workerTransportRuntimeFactory);
            return this;
        }

        public GatewayBuilder maxConnections(int maxConnections) {
            config.setMaxConnections(maxConnections);
            return this;
        }

        public GatewayBuilder inputQueue(MessageQueue<Envelope> inputQueue) {
            config.setInputQueue(inputQueue);
            return this;
        }

        public GatewayBuilder outputQueue(MessageQueue<Envelope> outputQueue) {
            config.setOutputQueue(outputQueue);
            return this;
        }

        public GatewayBuilder apiMode(String inputApiUrl, String outputApiUrl, String apiKey) {
            config.setTransporterType(MessageTransporterFactory.TransporterType.API_BASED);
            config.setInputApiUrl(inputApiUrl);
            config.setOutputApiUrl(outputApiUrl);
            config.setApiKey(apiKey);
            return this;
        }

        public GatewayBuilder queueMode() {
            config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
            return this;
        }

        /**
         * Overrides the default worker system-event channel. Useful for custom
         * transport adapters or testing with a mock channel.
         */
        public GatewayBuilder systemEventChannel(WorkerSystemEventChannel channel) {
            config.setCustomSystemEventChannel(channel);
            return this;
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

        /**
         * @deprecated Mock/bootstrap data should be wired through
         * {@link #bootstrapDataProvider(MassBootstrapDataProvider)}.
         */
        @Deprecated(forRemoval = false)
        public EngineBuilder mockData(String workerConfigPath, String workerContextConfigPath, String taskConfigPath, String ruleConfigPath) {
            return this;
        }

        /**
         * @deprecated Mock/bootstrap data should be wired through
         * {@link #bootstrapDataProvider(MassBootstrapDataProvider)}.
         */
        @Deprecated(forRemoval = false)
        public EngineBuilder mockData(String mockConfigPath) {
            return this;
        }
    }
}
