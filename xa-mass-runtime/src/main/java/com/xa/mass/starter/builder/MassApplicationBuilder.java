package com.xa.mass.starter.builder;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Mass 应用构建器
 * 提供流式API来构建MassApplication实例，支持多种预设配置和自定义配置
 *
 * 架构说明：
 * - MassApplicationBuilder: 负责配置聚合和参数验证
 * - MassApplication: 负责组件生命周期管理
 * - MassGateway/MassEngine: 直接通过配置对象实例化
 */
public class MassApplicationBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MassApplicationBuilder.class);

    // 直接管理配置参数，不再依赖 MassApplicationConfig
    private int serverPort = 8080;
    private String webSocketPath = "/ws";
    private GatewayConfig gatewayConfig = new GatewayConfig();
    private EngineConfig engineConfig = new EngineConfig();

    private MassApplicationBuilder() {
    }

    /**
     * 创建构建器实例
     */
    public static MassApplicationBuilder create() {
        return new MassApplicationBuilder();
    }

    /**
     * 快速创建开发环境应用
     */
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
     * 快速创建生产环境应用
     */
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

    /**
     * 快速创建API模式应用
     */
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

    /**
     * 快速创建测试环境应用
     */
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

    /**
     * 配置服务器
     */
    public MassApplicationBuilder server(int port) {
        return server(port, "/ws");
    }

    /**
     * 配置服务器
     */
    public MassApplicationBuilder server(int port, String webSocketPath) {
        this.serverPort = port;
        this.webSocketPath = webSocketPath;
        return this;
    }

    /**
     * 配置网关
     */
    public MassApplicationBuilder gateway(Consumer<GatewayBuilder> gatewayConfigurator) {
        GatewayBuilder gatewayBuilder = new GatewayBuilder(gatewayConfig);
        gatewayConfigurator.accept(gatewayBuilder);
        return this;
    }

    /**
     * 配置引擎
     */
    public MassApplicationBuilder engine(Consumer<EngineBuilder> engineConfigurator) {
        EngineBuilder engineBuilder = new EngineBuilder(engineConfig);
        engineConfigurator.accept(engineBuilder);
        return this;
    }

    /**
     * 构建MassApplication实例
     */
    public MassApplication build() {
        logger.info("🔨 Building MassApplication with configuration: port={}, gateway={}, engine={}",
                serverPort,
                gatewayConfig.isEnabled(),
                engineConfig.isEnabled());

        // 根据配置构建MassEngine（不需要dispatcherContext）
        MassEngine engine = null;
        if (engineConfig.isEnabled()) {
            engine = new MassEngine(engineConfig);
            logger.info("✅ MassEngine built");
        } else {
            logger.info("⚙️ MassEngine is disabled, skipping build");
        }

        // 创建MassApplication实例，传递已构建的engine和配置
        // MassGateway将在MassApplication.initializeComponents()中构建，因为需要dispatcherContext
        return new MassApplication(engine, serverPort, webSocketPath, gatewayConfig, engineConfig);
    }

    /**
     * 网关配置构建器
     */
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

    /**
     * 引擎配置构建器
     */
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

        public EngineBuilder mockData(String deviceConfigPath, String tokenConfigPath, String taskConfigPath, String ruleConfigPath) {
            config.setDeviceConfigPath(deviceConfigPath);
            config.setTokenConfigPath(tokenConfigPath);
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

        public EngineBuilder deviceManager(DeviceManager deviceManager) {
            config.setDeviceManager(deviceManager);
            return this;
        }

        public EngineBuilder ruleManager(RuleManager<Map<String, Object>> ruleManager) {
            config.setRuleManager(ruleManager);
            return this;
        }
    }
} 
