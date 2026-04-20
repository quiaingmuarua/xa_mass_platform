package com.xa.mass.starter.builder;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
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
    private String webSocketPath = "/ws";
    private GatewayConfig gatewayConfig = new GatewayConfig();
    private EngineConfig engineConfig = new EngineConfig();

    private MassApplicationBuilder() {
    }

    public static MassApplicationBuilder create() {
        return new MassApplicationBuilder();
    }

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
        return server(port, "/ws");
    }

    public MassApplicationBuilder server(int port, String webSocketPath) {
        this.serverPort = port;
        this.webSocketPath = webSocketPath;
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
        logger.info("Building MassApplication with configuration: port={}, gateway={}, engine={}",
                serverPort,
                gatewayConfig.isEnabled(),
                engineConfig.isEnabled());

        MassEngine engine = null;
        if (engineConfig.isEnabled()) {
            engine = new MassEngine(engineConfig);
            logger.info("MassEngine built");
        } else {
            logger.info("MassEngine is disabled, skipping build");
        }

        return new MassApplication(engine, serverPort, webSocketPath, gatewayConfig, engineConfig);
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

        public EngineBuilder mockData(String workerConfigPath, String workerContextConfigPath, String taskConfigPath, String ruleConfigPath) {
            config.setWorkerConfigPath(workerConfigPath);
            config.setWorkerContextConfigPath(workerContextConfigPath);
            config.setTaskConfigPath(taskConfigPath);
            config.setRuleConfigPath(ruleConfigPath);
            return this;
        }

        public EngineBuilder mockData(String mockConfigPath) {
            config.setMockConfigPath(mockConfigPath);
            return this;
        }

        public EngineBuilder scheduler(SimpleTaskScheduler scheduler) {
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
