package com.xa.mass.starter;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.server.MassServerBuilder;
import com.xa.mass.gateway.server.MassServerConfig;
import com.xa.mass.gateway.server.MassServerStater;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mass 应用主程序
 * 统一管理系统的启动流程，包括网关、引擎等组件的初始化
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    // 直接管理已构建的组件和配置参数
    private final int serverPort;
    private final String webSocketPath;
    private final GatewayConfig gatewayConfig;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final MassEngine engine; // 可能为null（当engine被禁用时）
    private MassGateway massGateway; // 将在initializeComponents中构建（当gateway被启用时）
    private DispatchRuntimeContext dispatcherContext;
    private MassServerStater serverStater;

    public MassApplication(MassEngine engine, int serverPort, String webSocketPath, GatewayConfig gatewayConfig, EngineConfig engineConfig) {
        this.engine = engine;
        this.serverPort = serverPort;
        this.webSocketPath = webSocketPath;
        this.gatewayConfig = gatewayConfig;
        this.engineConfig = engineConfig;
    }

    /**
     * 启动整个 Mass 应用
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.info("Mass Application is already running, skipping duplicate start");
            return;
        }
        logger.info("🚀 Starting Mass Application...");

        try {
            // 1. 初始化核心组件
            initializeComponents();

            // 2. 根据配置启动网关
            if (gatewayConfig.isEnabled()) {
                startGateway();
            } else {
                logger.info("🌐 MassGateway is disabled, skipping start");
            }

            // 3. 根据配置启动引擎
            if (engineConfig.isEnabled()) {
                startEngine();
            } else {
                logger.info("⚙️ MassEngine is disabled, skipping start");
            }

            // 4. 启动消息分发器
            startMessageDispatcher();

            // 5. 启动 WebSocket 服务器
            startWebSocketServer();

            logger.info("✅ Mass Application started successfully!");

        } catch (Exception e) {
            running.set(false);
            logger.error("❌ Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }

    /**
     * 停止整个 Mass 应用
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            logger.info("Mass Application is not running, skipping stop");
            return;
        }
        logger.info("🛑 Stopping Mass Application...");

        try {
            // 1. Stop the message dispatcher first so in-flight messages can drain
            //    before the network layer shuts down.
            if (massGateway != null && gatewayConfig.isEnabled()) {
                massGateway.stop();
            }

            // 2. Stop the engine (task processing) after the dispatcher has drained.
            if (engine != null && engineConfig.isEnabled()) {
                engine.stop();
            }

            // 3. Shut down the Netty WebSocket server last.
            if (serverStater != null) {
                serverStater.stop();
            }

            logger.info("✅ Mass Application stopped successfully!");

        } catch (Exception e) {
            logger.error("❌ Error stopping Mass Application", e);
        }
    }

    /**
     * 初始化核心组件
     */
    private void initializeComponents() {
        logger.info("🔧 Initializing core components...");

        try {
            // 初始化会话管理器
            ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
            logger.info("✅ Session manager initialized");

            // 创建消息传输器
            MessageTransporter messageTransporter = gatewayConfig.createMessageTransporter();
            logger.info("✅ Message transporter created");

            // 创建消息编解码器
            MessageCodec messageCodec = gatewayConfig.createMessageCodec();
            logger.info("✅ Message codec created");

            // 创建分发器上下文
            dispatcherContext = new DispatcherContext(messageTransporter, sessionManager, messageCodec);
            logger.info("✅ Dispatcher context created");

            // 注册到注册表
            try {
                DispatcherContextRegistry.register(dispatcherContext);
                logger.info("✅ Dispatcher context registered");
            } catch (Exception e) {
                logger.error("❌ Failed to register dispatcher context", e);
                throw e;
            }

            // 注册消息处理器
            MessageHandlerRegistry messageHandlerRegistry = new MessageHandlerRegistry();
            messageHandlerRegistry.autoRegister();
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                messageHandlerRegistry.register(null, MessageType.TASK, "step",
                        new GatewayTaskResultHandler(engineConfig.getTaskManager()));
            }
            dispatcherContext.setMessageHandlerRegistry(messageHandlerRegistry);
            logger.info("✅ Message handler registry initialized");

            // 注册中间件
            MiddlewareRegistry.autoRegister();
            logger.info("✅ Middleware registry initialized");

            // 根据配置构建MassGateway（需要dispatcherContext）
            if (gatewayConfig.isEnabled()) {
                engineConfig.setTaskMsgDispatchListener(new GatewayTaskMsgPublisher(dispatcherContext));
                massGateway = new MassGateway(gatewayConfig, dispatcherContext);
                logger.info("✅ MassGateway built");
            } else {
                logger.info("🌐 MassGateway is disabled, skipping build");
            }

        } catch (Exception e) {
            logger.error("❌ Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }

        logger.info("✅ Core components initialized");
    }

    /**
     * 启动网关
     */
    private void startGateway() {
        logger.info("🌐 Starting MassGateway...");
        if (massGateway != null) {
            massGateway.start();
            logger.info("✅ MassGateway started");
        } else {
            logger.error("❌ MassGateway is null");
        }
    }

    /**
     * 启动引擎
     */
    private void startEngine() {
        logger.info("⚙️ Starting MassEngine...");
        if (engine != null) {
            engine.start();
            logger.info("✅ MassEngine started");
        } else {
            logger.error("❌ MassEngine is null - check if engine is enabled in config");
        }
    }

    /**
     * 启动消息分发器
     */
    private void startMessageDispatcher() {
        // 消息分发器现在由 MassGateway 管理，不需要单独启动
        logger.info("📨 Message Dispatcher is managed by MassGateway");
    }

    /**
     * 启动 WebSocket 服务器
     */
    private void startWebSocketServer() {
        logger.info("🔌 Starting WebSocket Server...");

        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(serverPort)
                .withWebSocketPath(webSocketPath)
                .withDispatcherContext(dispatcherContext)
                .build();

        serverStater = new MassServerStater(serverConfig);
        serverStater.start();

        logger.info("✅ WebSocket Server started on port {}", serverPort);
    }

    /**
     * 获取分发器上下文
     */
    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    /**
     * 检查应用是否正在运行
     */
    public boolean isRunning() {
        return running.get() && serverStater != null && serverStater.isRunning();
    }

    // 注册mock消息处理器
    private void registerMockMessageHandlers(DispatchRuntimeContext dispatcherContext) {
        logger.info("📝 注册Mock消息处理器...");
        // 注册任务消息处理器
        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler taskHandler = msg -> {
            logger.info("[Gateway Handler] 处理任务消息: {}", msg);
            return new java.util.ArrayList<>();
        };
        // 注册设备消息处理器
        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler deviceHandler = msg -> {
            logger.info("[Gateway Handler] 处理设备消息: {}", msg);
            return new java.util.ArrayList<>();
        };
        dispatcherContext.getMessageHandlerRegistry().register("mock-task", com.xa.mass.gateway.model.enums.MessageType.TASK, "", taskHandler);
        dispatcherContext.getMessageHandlerRegistry().register("mock-device", com.xa.mass.gateway.model.enums.MessageType.STATUS, "", deviceHandler);
        logger.info("✅ Mock消息处理器注册完成");
    }

    // mock数据加载
    public void loadMockData(MassEngine engine, EngineConfig config) {
        logger.info("📊 加载 Mock 数据...");
        try {
            com.google.gson.JsonObject root = engineConfig.getMockConfigRoot();
            logger.info("✅ Mock 配置加载成功");
            if (root.has("devices")) {
                java.util.List<com.xa.mass.base.model.Device> devices = new java.util.ArrayList<>();
                com.google.gson.JsonElement deviceElem = root.get("devices");
                if (deviceElem.isJsonArray()) {
                    for (com.google.gson.JsonElement dsl : deviceElem.getAsJsonArray()) {
                        devices.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateDevices(dsl.toString()));
                    }
                } else {
                    devices.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateDevices(deviceElem.toString()));
                }
                logger.info("📱 生成 {} 个设备", devices.size());
                for (com.xa.mass.base.model.Device device : devices) {
                    normalizeMockDevice(device);
                    engine.addDevice(device);
                    ensureMockToken(engine, device);
                    logger.debug("添加设备: {} (分组: {}, 状态: {})", device.getDeviceId(), device.getGroupId(), device.getStatus());
                }
                verifyDeviceData(engine);
            }
            if (root.has("tasks")) {
                java.util.List<com.xa.mass.engine.model.TaskCreateRequestDto> taskDtos = new java.util.ArrayList<>();
                com.google.gson.JsonElement taskElem = root.get("tasks");
                if (taskElem.isJsonArray()) {
                    for (com.google.gson.JsonElement dsl : taskElem.getAsJsonArray()) {
                        taskDtos.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateTasks(dsl.toString()));
                    }
                } else {
                    taskDtos.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateTasks(taskElem.toString()));
                }
                logger.info("📋 生成 {} 个任务", taskDtos.size());
                for (com.xa.mass.engine.model.TaskCreateRequestDto dto : taskDtos) {
                    engine.createTask(dto);
                    logger.debug("创建任务: {} (国家: {}, 项目: {}, 数量: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject(), dto.getBatchSize());
                }
            }
            logger.info("✅ Mock 数据加载完成");
        } catch (Exception e) {
            logger.error("❌ Mock 数据加载失败", e);
            throw new RuntimeException(e);
        }
    }

    public void verifyDeviceData(MassEngine engine) {
        com.xa.mass.engine.DeviceManager deviceManager = engine.getDeviceManager();
        if (deviceManager != null) {
            java.util.List<Device> allDevices = deviceManager.getAllDevices();
            java.util.List<Device> usDevices = deviceManager.getDevicesByCountry("us");
            java.util.List<Device> gbDevices = deviceManager.getDevicesByCountry("gb");
            logger.info("📊 设备数据验证 - 总计: {}, 美国: {}, 英国: {}", allDevices.size(), usDevices.size(), gbDevices.size());
            for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
                Device device = allDevices.get(i);
                com.xa.mass.base.model.Token token = deviceManager.getToken(device.getDeviceId());
                logger.info("设备 {}: ID={}, 分组={}, 状态={}, Token={}, Token状态={}", i + 1, device.getDeviceId(), device.getGroupId(), device.getStatus(), token != null ? token.getTokenId() : "null", token != null ? token.getStatus() : "null");
            }
        }
    }

    void normalizeMockDevice(Device device) {
        if (device == null) {
            return;
        }
        if (device.getGroupId() != null) {
            device.setGroupId(device.getGroupId().toLowerCase());
        }
        List<Project> supportedProjects = normalizeSupportedProjects(device);
        if (!supportedProjects.isEmpty()) {
            device.setSupportedProjects(supportedProjects);
        }
    }

    void ensureMockToken(MassEngine engine, Device device) {
        if (engine == null || device == null || engine.getDeviceManager() == null) {
            return;
        }
        if (engine.getDeviceManager().getToken(device.getDeviceId()) != null) {
            return;
        }
        Token token = new Token();
        token.setTokenId("token-" + device.getDeviceId());
        token.setDeviceId(device.getDeviceId());
        token.setChannel(device.getGroupId());
        token.setStatus(TokenStatus.LOGIN_READY);
        engine.addToken(token);
    }

    private List<Project> normalizeSupportedProjects(Device device) {
        if (device.getSupportedProjects() == null) {
            return defaultSupportedProjects();
        }
        List<?> rawProjects = (List<?>) device.getSupportedProjects();
        if (rawProjects.isEmpty()) {
            return defaultSupportedProjects();
        }

        List<Project> normalized = new ArrayList<>();
        for (Object rawProject : rawProjects) {
            if (rawProject instanceof Project project) {
                normalized.add(project);
                continue;
            }
            if (rawProject == null) {
                continue;
            }
            normalized.add(Project.fromCode(String.valueOf(rawProject)));
        }
        return normalized.stream().filter(Objects::nonNull).distinct().toList();
    }

    private List<Project> defaultSupportedProjects() {
        return List.of(Project.DEMO_APP, Project.TEST_APP);
    }

    public MassEngine getEngine() {
        return engine;
    }
}
