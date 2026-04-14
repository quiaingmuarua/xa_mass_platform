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
 * Mass 搴旂敤涓荤▼搴?
 * 缁熶竴绠＄悊绯荤粺鐨勫惎鍔ㄦ祦绋嬶紝鍖呮嫭缃戝叧銆佸紩鎿庣瓑缁勪欢鐨勫垵濮嬪寲
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    // 鐩存帴绠＄悊宸叉瀯寤虹殑缁勪欢鍜岄厤缃弬鏁?
    private final int serverPort;
    private final String webSocketPath;
    private final GatewayConfig gatewayConfig;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final MassEngine engine; // 鍙兘涓簄ull锛堝綋engine琚鐢ㄦ椂锛?
    private MassGateway massGateway; // 灏嗗湪initializeComponents涓瀯寤猴紙褰揼ateway琚惎鐢ㄦ椂锛?
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
     * 鍚姩鏁翠釜 Mass 搴旂敤
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.info("Mass Application is already running, skipping duplicate start");
            return;
        }
        logger.info("馃殌 Starting Mass Application...");

        try {
            // 1. 鍒濆鍖栨牳蹇冪粍浠?
            initializeComponents();

            // 2. 鏍规嵁閰嶇疆鍚姩缃戝叧
            if (gatewayConfig.isEnabled()) {
                startGateway();
            } else {
                logger.info("馃寪 MassGateway is disabled, skipping start");
            }

            // 3. 鏍规嵁閰嶇疆鍚姩寮曟搸
            if (engineConfig.isEnabled()) {
                startEngine();
            } else {
                logger.info("鈿欙笍 MassEngine is disabled, skipping start");
            }

            // 4. 鍚姩娑堟伅鍒嗗彂鍣?
            startMessageDispatcher();

            // 5. 鍚姩 WebSocket 鏈嶅姟鍣?
            startWebSocketServer();

            logger.info("鉁?Mass Application started successfully!");

        } catch (Exception e) {
            running.set(false);
            logger.error("鉂?Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }

    /**
     * 鍋滄鏁翠釜 Mass 搴旂敤
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            logger.info("Mass Application is not running, skipping stop");
            return;
        }
        logger.info("馃洃 Stopping Mass Application...");

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

            logger.info("鉁?Mass Application stopped successfully!");

        } catch (Exception e) {
            logger.error("鉂?Error stopping Mass Application", e);
        }
    }

    /**
     * 鍒濆鍖栨牳蹇冪粍浠?
     */
    private void initializeComponents() {
        logger.info("馃敡 Initializing core components...");

        try {
            // 鍒濆鍖栦細璇濈鐞嗗櫒
            ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
            logger.info("鉁?Session manager initialized");

            // 鍒涘缓娑堟伅浼犺緭鍣?
            MessageTransporter messageTransporter = gatewayConfig.createMessageTransporter();
            logger.info("鉁?Message transporter created");

            // 鍒涘缓娑堟伅缂栬В鐮佸櫒
            MessageCodec messageCodec = gatewayConfig.createMessageCodec();
            logger.info("鉁?Message codec created");

            // 鍒涘缓鍒嗗彂鍣ㄤ笂涓嬫枃
            dispatcherContext = new DispatcherContext(messageTransporter, sessionManager, messageCodec);
            logger.info("鉁?Dispatcher context created");

            // 娉ㄥ唽鍒版敞鍐岃〃
            try {
                DispatcherContextRegistry.register(dispatcherContext);
                logger.info("鉁?Dispatcher context registered");
            } catch (Exception e) {
                logger.error("鉂?Failed to register dispatcher context", e);
                throw e;
            }

            // 娉ㄥ唽娑堟伅澶勭悊鍣?
            MessageHandlerRegistry messageHandlerRegistry = new MessageHandlerRegistry();
            messageHandlerRegistry.autoRegister();
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                messageHandlerRegistry.register(null, MessageType.TASK, "step",
                        new GatewayTaskResultHandler(engineConfig.getTaskManager()));
            }
            dispatcherContext.setMessageHandlerRegistry(messageHandlerRegistry);
            logger.info("鉁?Message handler registry initialized");

            // 娉ㄥ唽涓棿浠?
            MiddlewareRegistry.autoRegister();
            logger.info("鉁?Middleware registry initialized");

            // 鏍规嵁閰嶇疆鏋勫缓MassGateway锛堥渶瑕乨ispatcherContext锛?
            if (gatewayConfig.isEnabled()) {
                engineConfig.setTaskMsgDispatchListener(new GatewayTaskMsgPublisher(dispatcherContext));
                massGateway = new MassGateway(gatewayConfig, dispatcherContext);
                logger.info("鉁?MassGateway built");
            } else {
                logger.info("馃寪 MassGateway is disabled, skipping build");
            }

        } catch (Exception e) {
            logger.error("鉂?Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }

        logger.info("鉁?Core components initialized");
    }

    /**
     * 鍚姩缃戝叧
     */
    private void startGateway() {
        logger.info("馃寪 Starting MassGateway...");
        if (massGateway != null) {
            massGateway.start();
            logger.info("鉁?MassGateway started");
        } else {
            logger.error("鉂?MassGateway is null");
        }
    }

    /**
     * 鍚姩寮曟搸
     */
    private void startEngine() {
        logger.info("鈿欙笍 Starting MassEngine...");
        if (engine != null) {
            engine.start();
            logger.info("鉁?MassEngine started");
        } else {
            logger.error("鉂?MassEngine is null - check if engine is enabled in config");
        }
    }

    /**
     * 鍚姩娑堟伅鍒嗗彂鍣?
     */
    private void startMessageDispatcher() {
        // 娑堟伅鍒嗗彂鍣ㄧ幇鍦ㄧ敱 MassGateway 绠＄悊锛屼笉闇€瑕佸崟鐙惎鍔?
        logger.info("馃摠 Message Dispatcher is managed by MassGateway");
    }

    /**
     * 鍚姩 WebSocket 鏈嶅姟鍣?
     */
    private void startWebSocketServer() {
        logger.info("馃攲 Starting WebSocket Server...");

        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(serverPort)
                .withWebSocketPath(webSocketPath)
                .withDispatcherContext(dispatcherContext)
                .build();

        serverStater = new MassServerStater(serverConfig);
        serverStater.start();

        logger.info("鉁?WebSocket Server started on port {}", serverPort);
    }

    /**
     * 鑾峰彇鍒嗗彂鍣ㄤ笂涓嬫枃
     */
    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    /**
     * 妫€鏌ュ簲鐢ㄦ槸鍚︽鍦ㄨ繍琛?     */
    public boolean isRunning() {
        return running.get() && serverStater != null && serverStater.isRunning();
    }

    // 娉ㄥ唽mock娑堟伅澶勭悊鍣?
    private void registerMockMessageHandlers(DispatchRuntimeContext dispatcherContext) {
        logger.info("馃摑 娉ㄥ唽Mock娑堟伅澶勭悊鍣?..");
        // 娉ㄥ唽浠诲姟娑堟伅澶勭悊鍣?
        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler taskHandler = msg -> {
            logger.info("[Gateway Handler] 澶勭悊浠诲姟娑堟伅: {}", msg);
            return new java.util.ArrayList<>();
        };
        // 娉ㄥ唽璁惧娑堟伅澶勭悊鍣?
        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler deviceHandler = msg -> {
            logger.info("[Gateway Handler] 澶勭悊璁惧娑堟伅: {}", msg);
            return new java.util.ArrayList<>();
        };
        dispatcherContext.getMessageHandlerRegistry().register("mock-task", com.xa.mass.gateway.model.enums.MessageType.TASK, "", taskHandler);
        dispatcherContext.getMessageHandlerRegistry().register("mock-device", com.xa.mass.gateway.model.enums.MessageType.STATUS, "", deviceHandler);
        logger.info("鉁?Mock娑堟伅澶勭悊鍣ㄦ敞鍐屽畬鎴?);
    }

    // mock鏁版嵁鍔犺浇
    public void loadMockData(MassEngine engine, EngineConfig config) {
        logger.info("馃搳 鍔犺浇 Mock 鏁版嵁...");
        try {
            com.google.gson.JsonObject root = engineConfig.getMockConfigRoot();
            logger.info("鉁?Mock 閰嶇疆鍔犺浇鎴愬姛");
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
                logger.info("馃摫 鐢熸垚 {} 涓澶?, devices.size());
                for (com.xa.mass.base.model.Device device : devices) {
                    normalizeMockDevice(device);
                    engine.addDevice(device);
                    ensureMockToken(engine, device);
                    logger.debug("娣诲姞璁惧: {} (鍒嗙粍: {}, 鐘舵€? {})", device.getDeviceId(), device.getDeviceGroupId(), device.getStatus());
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
                logger.info("馃搵 鐢熸垚 {} 涓换鍔?, taskDtos.size());
                for (com.xa.mass.engine.model.TaskCreateRequestDto dto : taskDtos) {
                    engine.createTask(dto);
                    logger.debug("鍒涘缓浠诲姟: {} (鍥藉: {}, 椤圭洰: {}, 鏁伴噺: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject(), dto.getBatchSize());
                }
            }
            logger.info("鉁?Mock 鏁版嵁鍔犺浇瀹屾垚");
        } catch (Exception e) {
            logger.error("鉂?Mock 鏁版嵁鍔犺浇澶辫触", e);
            throw new RuntimeException(e);
        }
    }

    public void verifyDeviceData(MassEngine engine) {
        com.xa.mass.engine.DeviceManager deviceManager = engine.getDeviceManager();
        if (deviceManager != null) {
            java.util.List<Device> allDevices = deviceManager.getAllDevices();
            java.util.List<Device> usDevices = deviceManager.getDevicesByGroupId("us");
            java.util.List<Device> gbDevices = deviceManager.getDevicesByGroupId("gb");
            logger.info("馃搳 璁惧鏁版嵁楠岃瘉 - 鎬昏: {}, 缇庡浗: {}, 鑻卞浗: {}", allDevices.size(), usDevices.size(), gbDevices.size());
            for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
                Device device = allDevices.get(i);
                com.xa.mass.base.model.Token token = deviceManager.getToken(device.getDeviceId());
                logger.info("璁惧 {}: ID={}, 鍒嗙粍={}, 鐘舵€?{}, Token={}, Token鐘舵€?{}", i + 1, device.getDeviceId(), device.getDeviceGroupId(), device.getStatus(), token != null ? token.getTokenId() : "null", token != null ? token.getStatus() : "null");
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
