package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
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
 * Main runtime composition entry for engine, gateway, dispatcher, and server startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    private final int serverPort;
    private final String webSocketPath;
    private final GatewayConfig gatewayConfig;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final MassEngine engine;
    private MassGateway massGateway;
    private DispatchRuntimeContext dispatcherContext;
    private MassServerStater serverStater;

    public MassApplication(MassEngine engine, int serverPort, String webSocketPath,
                           GatewayConfig gatewayConfig, EngineConfig engineConfig) {
        this.engine = engine;
        this.serverPort = serverPort;
        this.webSocketPath = webSocketPath;
        this.gatewayConfig = gatewayConfig;
        this.engineConfig = engineConfig;
    }

    /**
     * Starts the composed runtime.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.info("Mass Application is already running, skipping duplicate start");
            return;
        }
        logger.info("Starting Mass Application");

        try {
            initializeComponents();

            if (gatewayConfig.isEnabled()) {
                startGateway();
            } else {
                logger.info("MassGateway is disabled, skipping start");
            }

            if (engineConfig.isEnabled()) {
                startEngine();
            } else {
                logger.info("MassEngine is disabled, skipping start");
            }

            startMessageDispatcher();
            startWebSocketServer();

            logger.info("Mass Application started successfully");
        } catch (Exception e) {
            running.set(false);
            logger.error("Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }

    /**
     * Stops the composed runtime.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            logger.info("Mass Application is not running, skipping stop");
            return;
        }
        logger.info("Stopping Mass Application");

        try {
            if (massGateway != null && gatewayConfig.isEnabled()) {
                massGateway.stop();
            }

            if (engine != null && engineConfig.isEnabled()) {
                engine.stop();
            }

            if (serverStater != null) {
                serverStater.stop();
            }

            logger.info("Mass Application stopped successfully");
        } catch (Exception e) {
            logger.error("Error stopping Mass Application", e);
        }
    }

    /**
     * Initializes dispatcher, message handlers, middleware, and gateway composition.
     */
    private void initializeComponents() {
        logger.info("Initializing core components");

        try {
            ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
            logger.info("Session manager initialized");

            MessageTransporter messageTransporter = gatewayConfig.createMessageTransporter();
            logger.info("Message transporter created");

            MessageCodec messageCodec = gatewayConfig.createMessageCodec();
            logger.info("Message codec created");

            dispatcherContext = new DispatcherContext(messageTransporter, sessionManager, messageCodec);
            logger.info("Dispatcher context created");

            DispatcherContextRegistry.register(dispatcherContext);
            logger.info("Dispatcher context registered");

            MessageHandlerRegistry messageHandlerRegistry = new MessageHandlerRegistry();
            messageHandlerRegistry.autoRegister();
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                messageHandlerRegistry.register(
                        null,
                        MessageType.TASK,
                        "step",
                        new GatewayTaskResultHandler(engineConfig.getTaskManager())
                );
            }
            dispatcherContext.setMessageHandlerRegistry(messageHandlerRegistry);
            logger.info("Message handler registry initialized");

            MiddlewareRegistry.autoRegister();
            logger.info("Middleware registry initialized");

            if (gatewayConfig.isEnabled()) {
                engineConfig.setTaskMsgDispatchListener(new GatewayTaskMsgPublisher(dispatcherContext));
                massGateway = new MassGateway(gatewayConfig, dispatcherContext);
                logger.info("MassGateway built");
            } else {
                logger.info("MassGateway is disabled, skipping build");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }

        logger.info("Core components initialized");
    }

    /**
     * Starts gateway processing.
     */
    private void startGateway() {
        logger.info("Starting MassGateway");
        if (massGateway != null) {
            massGateway.start();
            logger.info("MassGateway started");
        } else {
            logger.error("MassGateway is null");
        }
    }

    /**
     * Starts engine processing.
     */
    private void startEngine() {
        logger.info("Starting MassEngine");
        if (engine != null) {
            engine.start();
            logger.info("MassEngine started");
        } else {
            logger.error("MassEngine is null - check if engine is enabled in config");
        }
    }

    /**
     * Dispatcher lifecycle is currently owned by MassGateway.
     */
    private void startMessageDispatcher() {
        logger.info("Message Dispatcher is managed by MassGateway");
    }

    /**
     * Starts the WebSocket server.
     */
    private void startWebSocketServer() {
        logger.info("Starting WebSocket Server");

        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(serverPort)
                .withWebSocketPath(webSocketPath)
                .withDispatcherContext(dispatcherContext)
                .build();

        serverStater = new MassServerStater(serverConfig);
        serverStater.start();

        logger.info("WebSocket Server started on port {}", serverPort);
    }

    /**
     * Returns the live dispatch runtime context.
     */
    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    /**
     * Returns whether the composed runtime is currently running.
     */
    public boolean isRunning() {
        return running.get() && serverStater != null && serverStater.isRunning();
    }

    private void registerMockMessageHandlers(DispatchRuntimeContext dispatcherContext) {
        logger.info("Registering mock gateway message handlers");

        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler taskHandler = msg -> {
            logger.info("[Gateway Handler] Received mock task message: {}", msg);
            return new ArrayList<>();
        };
        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler deviceHandler = msg -> {
            logger.info("[Gateway Handler] Received mock device status message: {}", msg);
            return new ArrayList<>();
        };

        dispatcherContext.getMessageHandlerRegistry().register(
                "mock-task", MessageType.TASK, "", taskHandler
        );
        dispatcherContext.getMessageHandlerRegistry().register(
                "mock-device", MessageType.STATUS, "", deviceHandler
        );
        logger.info("Mock gateway message handlers registered");
    }

    public void loadMockData(MassEngine engine, EngineConfig config) {
        logger.info("Loading mock data");

        try {
            com.google.gson.JsonObject root = config.getMockConfigRoot();
            logger.info("Mock config loaded successfully");

            if (root.has("devices")) {
                List<Device> devices = new ArrayList<>();
                com.google.gson.JsonElement deviceElem = root.get("devices");
                if (deviceElem.isJsonArray()) {
                    for (com.google.gson.JsonElement dsl : deviceElem.getAsJsonArray()) {
                        devices.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateDevices(dsl.toString()));
                    }
                } else {
                    devices.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateDevices(deviceElem.toString()));
                }

                logger.info("Generated {} mock devices", devices.size());
                for (Device device : devices) {
                    normalizeMockDevice(device);
                    engine.addDevice(device);
                    ensureMockToken(engine, device);
                    logger.debug("Loaded mock device: {} (deviceGroupId: {}, status: {})",
                            device.getDeviceId(), device.getDeviceGroupId(), device.getStatus());
                }
                verifyDeviceData(engine);
            }

            if (root.has("tasks")) {
                List<com.xa.mass.engine.model.TaskCreateRequestDto> taskDtos = new ArrayList<>();
                com.google.gson.JsonElement taskElem = root.get("tasks");
                if (taskElem.isJsonArray()) {
                    for (com.google.gson.JsonElement dsl : taskElem.getAsJsonArray()) {
                        taskDtos.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateTasks(dsl.toString()));
                    }
                } else {
                    taskDtos.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateTasks(taskElem.toString()));
                }

                logger.info("Generated {} mock task requests", taskDtos.size());
                for (com.xa.mass.engine.model.TaskCreateRequestDto dto : taskDtos) {
                    engine.createTask(dto);
                    logger.debug("Loaded mock task request: {} (routingCountryCode: {}, project: {}, batchSize: {})",
                            dto.getTaskName(), dto.getCountryCode(), dto.getProject(), dto.getBatchSize());
                }
            }

            logger.info("Mock data load completed");
        } catch (Exception e) {
            logger.error("Mock data load failed", e);
            throw new RuntimeException(e);
        }
    }

    public void verifyDeviceData(MassEngine engine) {
        com.xa.mass.engine.DeviceManager deviceManager = engine.getDeviceManager();
        if (deviceManager != null) {
            List<Device> allDevices = deviceManager.getAllDevices();
            List<Device> usDevices = deviceManager.getDevicesByGroupId("us");
            List<Device> gbDevices = deviceManager.getDevicesByGroupId("gb");

            logger.info("Verified mock devices: total={}, usGroup={}, gbGroup={}",
                    allDevices.size(), usDevices.size(), gbDevices.size());

            for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
                Device device = allDevices.get(i);
                Token token = deviceManager.getToken(device.getDeviceId());
                logger.info("Device {}: id={}, deviceGroupId={}, status={}, tokenId={}, tokenStatus={}",
                        i + 1,
                        device.getDeviceId(),
                        device.getDeviceGroupId(),
                        device.getStatus(),
                        token != null ? token.getTokenId() : "null",
                        token != null ? token.getStatus() : "null");
            }
        }
    }

    void normalizeMockDevice(Device device) {
        if (device == null) {
            return;
        }
        if (device.getDeviceGroupId() != null) {
            device.setDeviceGroupId(device.getDeviceGroupId().toLowerCase());
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
        token.setChannel(device.getDeviceGroupId());
        token.setStatus(TokenStatus.LOGIN_READY);
        if (device.getDeviceGroupId() != null) {
            token.setAttributes(java.util.Map.of("country", device.getDeviceGroupId()));
        }
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
