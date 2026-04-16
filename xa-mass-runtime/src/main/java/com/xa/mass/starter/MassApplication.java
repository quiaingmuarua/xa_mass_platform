package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.rules.RuleDefinition;
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
                com.xa.mass.starter.worker.WebSocketWorkerAdapter workerAdapter =
                        new com.xa.mass.starter.worker.WebSocketWorkerAdapter(
                                dispatcherContext, engineConfig.getTaskManager());
                engineConfig.setTaskMsgDispatchListener(workerAdapter);
                messageHandlerRegistry.register(
                        null,
                        MessageType.TASK,
                        "step",
                        workerAdapter
                );
            }
            dispatcherContext.setMessageHandlerRegistry(messageHandlerRegistry);
            logger.info("Message handler registry initialized");

            MiddlewareRegistry.autoRegister();
            logger.info("Middleware registry initialized");

            if (gatewayConfig.isEnabled()) {
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

    private void startGateway() {
        logger.info("Starting MassGateway");
        if (massGateway != null) {
            massGateway.start();
            logger.info("MassGateway started");
        } else {
            logger.error("MassGateway is null");
        }
    }

    private void startEngine() {
        logger.info("Starting MassEngine");
        if (engine != null) {
            engine.start();
            logger.info("MassEngine started");
        } else {
            logger.error("MassEngine is null - check if engine is enabled in config");
        }
    }

    private void startMessageDispatcher() {
        logger.info("Message Dispatcher is managed by MassGateway");
    }

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

    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    public boolean isRunning() {
        return running.get() && serverStater != null && serverStater.isRunning();
    }

    private void registerMockMessageHandlers(DispatchRuntimeContext dispatcherContext) {
        logger.info("Registering mock gateway message handlers");

        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler taskHandler = msg -> {
            logger.info("[Gateway Handler] Received mock task message: {}", msg);
            return new ArrayList<>();
        };
        com.xa.mass.gateway.dispatcher.handler.MassMessageHandler workerHandler = msg -> {
            logger.info("[Gateway Handler] Received mock worker status message: {}", msg);
            return new ArrayList<>();
        };

        dispatcherContext.getMessageHandlerRegistry().register(
                "mock-task", MessageType.TASK, "", taskHandler
        );
        dispatcherContext.getMessageHandlerRegistry().register(
                "mock-worker", MessageType.STATUS, "", workerHandler
        );
        logger.info("Mock gateway message handlers registered");
    }

    public void loadMockData(MassEngine engine, EngineConfig config) {
        logger.info("Loading mock data");

        try {
            com.google.gson.JsonObject root = config.getMockConfigRoot();
            logger.info("Mock config loaded successfully");

            if (root.has("workers")) {
                List<Worker> workers = new ArrayList<>();
                com.google.gson.JsonElement workerElem = root.get("workers");
                if (workerElem.isJsonArray()) {
                    for (com.google.gson.JsonElement dsl : workerElem.getAsJsonArray()) {
                        workers.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateWorkers(dsl.toString()));
                    }
                } else {
                    workers.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateWorkers(workerElem.toString()));
                }

                logger.info("Generated {} mock workers", workers.size());
                for (Worker worker : workers) {
                    normalizeMockWorker(worker);
                    engine.addWorker(worker);
                    logger.debug("Loaded mock worker: {} (workerGroupId: {}, status: {})",
                            worker.getWorkerId(), worker.getWorkerGroupId(), worker.getStatus());
                }
            }

            if (root.has("workerContexts")) {
                List<WorkerContext> workerContexts = new ArrayList<>();
                com.google.gson.JsonElement wcElem = root.get("workerContexts");
                if (wcElem.isJsonArray()) {
                    for (com.google.gson.JsonElement dsl : wcElem.getAsJsonArray()) {
                        workerContexts.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateWorkerContexts(dsl.toString()));
                    }
                } else {
                    workerContexts.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateWorkerContexts(wcElem.toString()));
                }

                logger.info("Generated {} mock workerContexts", workerContexts.size());
                for (WorkerContext wc : workerContexts) {
                    normalizeMockWorkerContext(wc);
                    if (wc.getWorkerId() == null || wc.getWorkerId().isBlank()) {
                        logger.warn("Skipping mock workerContext {} because workerId is missing", wc.getWorkerContextId());
                        continue;
                    }
                    engine.addWorkerContext(wc);
                    logger.debug("Loaded mock workerContext: {} (workerId: {}, channel: {}, attributes: {})",
                            wc.getWorkerContextId(), wc.getWorkerId(), wc.getChannel(), wc.getAttributes());
                }
            }

            ensureMockWorkerContexts(engine);
            verifyWorkerData(engine);
            loadMockRules(config);

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

    public void verifyWorkerData(MassEngine engine) {
        com.xa.mass.engine.WorkerManager workerManager = engine.getWorkerManager();
        if (workerManager != null) {
            List<Worker> allWorkers = workerManager.getAllWorkers();
            List<Worker> usWorkers = workerManager.getWorkersByGroupId("us");
            List<Worker> gbWorkers = workerManager.getWorkersByGroupId("gb");

            logger.info("Verified mock workers: total={}, usGroup={}, gbGroup={}",
                    allWorkers.size(), usWorkers.size(), gbWorkers.size());

            for (int i = 0; i < Math.min(3, allWorkers.size()); i++) {
                Worker worker = allWorkers.get(i);
                List<WorkerContext> workerContexts = workerManager.getWorkerContexts(worker.getWorkerId());
                WorkerContext wc = workerContexts.isEmpty() ? null : workerContexts.get(0);
                logger.info("Worker {}: id={}, workerGroupId={}, status={}, workerContextCount={}, sampleWorkerContextId={}, sampleWorkerContextStatus={}",
                        i + 1,
                        worker.getWorkerId(),
                        worker.getWorkerGroupId(),
                        worker.getStatus(),
                        workerContexts.size(),
                        wc != null ? wc.getWorkerContextId() : "null",
                        wc != null ? wc.getStatus() : "null");
            }
        }
    }

    void normalizeMockWorker(Worker worker) {
        if (worker == null) {
            return;
        }
        if (worker.getWorkerGroupId() != null) {
            worker.setWorkerGroupId(worker.getWorkerGroupId().toLowerCase());
        }
        List<String> supportedProjects = normalizeSupportedProjects(worker);
        if (!supportedProjects.isEmpty()) {
            worker.setSupportedProjects(supportedProjects);
        }
    }

    void normalizeMockWorkerContext(WorkerContext wc) {
        if (wc == null) {
            return;
        }
        if (wc.getChannel() != null) {
            wc.setChannel(wc.getChannel().toLowerCase());
        }
        if (wc.getStatus() == null) {
            wc.setStatus(WorkerContextStatus.IDLE);
        }
        if (!wc.getAttributes().isEmpty()) {
            java.util.Map<String, String> normalizedAttributes = new java.util.LinkedHashMap<>(wc.getAttributes());
            String country = normalizedAttributes.get("country");
            if (country != null) {
                normalizedAttributes.put("country", country.toLowerCase());
            }
            wc.setAttributes(normalizedAttributes);
        }
    }

    void ensureMockWorkerContexts(MassEngine engine) {
        if (engine == null || engine.getWorkerManager() == null) {
            return;
        }
        for (Worker worker : engine.getWorkerManager().getAllWorkers()) {
            ensureMockWorkerContext(engine, worker);
        }
    }

    void ensureMockWorkerContext(MassEngine engine, Worker worker) {
        if (engine == null || worker == null || engine.getWorkerManager() == null) {
            return;
        }
        if (!engine.getWorkerManager().getWorkerContexts(worker.getWorkerId()).isEmpty()) {
            return;
        }

        WorkerContext wc = new WorkerContext();
        wc.setWorkerContextId("wc-" + worker.getWorkerId());
        wc.setWorkerId(worker.getWorkerId());
        wc.setStatus(WorkerContextStatus.IDLE);
        engine.addWorkerContext(wc);
    }

    void loadMockRules(EngineConfig config) {
        if (config == null || config.getRuleManager() == null) {
            return;
        }

        com.google.gson.JsonObject root = config.getMockConfigRoot();
        if (!root.has("rules")) {
            return;
        }

        List<RuleDefinition> rules = new ArrayList<>();
        com.google.gson.JsonElement ruleElem = root.get("rules");
        if (ruleElem.isJsonArray()) {
            for (com.google.gson.JsonElement dsl : ruleElem.getAsJsonArray()) {
                rules.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateRules(dsl.toString()));
            }
        } else {
            rules.addAll(com.xa.mass.engine.monkey.MonkeyGenerator.generateRules(ruleElem.toString()));
        }

        if (rules.isEmpty()) {
            logger.info("Mock rules config is empty; keeping existing default rules ({})",
                    config.getRuleManager().getDefaultRules().size());
            return;
        }

        config.getRuleManager().clear();
        config.getRuleManager().addDefaultRules(rules);
        logger.info("Loaded {} explicit mock rules", rules.size());
    }

    private List<String> normalizeSupportedProjects(Worker worker) {
        List<String> projects = worker.getSupportedProjects();
        if (projects == null || projects.isEmpty()) {
            return defaultSupportedProjects();
        }
        return projects.stream().filter(Objects::nonNull).distinct().toList();
    }

    private List<String> defaultSupportedProjects() {
        return List.of("demoApp", "testApp");
    }

    public MassEngine getEngine() {
        return engine;
    }
}
