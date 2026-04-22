package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.debug.ManualDebugChatProtocol;
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
import com.xa.mass.starter.worker.PollingWorkerAdapter;
import com.xa.mass.starter.worker.TransportRoutingTaskMsgDispatchListener;
import com.xa.mass.starter.worker.WebSocketWorkerAdapter;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.worker.PollingWorkerSession;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.transport.WorkerEndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
    private PollingWorkerAdapter pollingWorkerAdapter;

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
            LogUtils.clearMdc();
            logger.info("Mass Application is already running, skipping duplicate start");
            return;
        }
        LogUtils.clearMdc();
        logger.info("Starting Mass Application");

        try {
            initializeComponents();

            if (gatewayConfig.isEnabled()) {
                startGateway();
            } else {
                logger.info("MassGateway is disabled, skipping start");
            }

            startMessageDispatcher();
            startTransportServer();

            LogUtils.clearMdc();
            logger.info("Mass Application started successfully");
        } catch (Exception e) {
            running.set(false);
            LogUtils.clearMdc();
            logger.error("Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            LogUtils.clearMdc();
            logger.info("Mass Application is not running, skipping stop");
            return;
        }
        LogUtils.clearMdc();
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

            LogUtils.clearMdc();
            logger.info("Mass Application stopped successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Error stopping Mass Application", e);
        }
    }

    private void initializeComponents() {
        logger.info("Initializing core components");

        try {
            ServerSessionManager sessionManager = ServerSessionManager.INSTANCE;
            logger.info("Worker endpoint registry initialized");

            MessageTransporter messageTransporter = gatewayConfig.createMessageTransporter();
            logger.info("Message transporter created");

            MessageCodec messageCodec = gatewayConfig.createMessageCodec();
            logger.info("Message codec created");

            dispatcherContext = new DispatcherContext(messageTransporter, sessionManager, messageCodec);
            logger.info("Dispatcher context created");

            DispatcherContextRegistry.register(dispatcherContext);
            logger.info("Dispatcher context registered");

            com.xa.mass.transport.channel.WorkerSystemEventChannel systemEventChannel =
                    gatewayConfig.getCustomSystemEventChannel() != null
                            ? gatewayConfig.getCustomSystemEventChannel()
                            : sessionManager.getSystemEventChannel();

            MessageHandlerRegistry messageHandlerRegistry =
                    new MessageHandlerRegistry(systemEventChannel);
            messageHandlerRegistry.autoRegister();
            TaskMsgDispatchListener taskMsgDispatchListener = null;
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                List<WorkerAdapter> workerAdapters = new ArrayList<>();
                pollingWorkerAdapter = new PollingWorkerAdapter(
                        engineConfig.getTaskManager(),
                        systemEventChannel
                );
                workerAdapters.add(pollingWorkerAdapter);

                if (gatewayConfig.isEnabled()) {
                    WebSocketWorkerAdapter workerAdapter = new WebSocketWorkerAdapter(
                            dispatcherContext, engineConfig.getTaskManager());
                    workerAdapters.add(workerAdapter);
                    messageHandlerRegistry.register(
                            null,
                            MessageType.TASK,
                            "step",
                            workerAdapter
                    );
                }

                taskMsgDispatchListener = workerAdapters.size() == 1
                        ? workerAdapters.get(0)
                        : new TransportRoutingTaskMsgDispatchListener(
                                engineConfig.getWorkerManager(),
                                workerAdapters,
                                gatewayConfig.isEnabled() ? WebSocketWorkerAdapter.PROTOCOL : PollingWorkerAdapter.PROTOCOL
                        );
            }
            messageHandlerRegistry.register(
                    null,
                    MessageType.EVENT,
                    ManualDebugChatProtocol.SUB_MSG_TYPE,
                    new ManualDebugMessageHandler()
            );
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

            if (engineConfig.isEnabled()) {
                startEngine(taskMsgDispatchListener);
            } else {
                logger.info("MassEngine is disabled, skipping start");
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

    private void startEngine(TaskMsgDispatchListener taskMsgDispatchListener) {
        logger.info("Starting MassEngine");
        if (engine != null) {
            engine.start(taskMsgDispatchListener);
            logger.info("MassEngine started");
        } else {
            logger.error("MassEngine is null - check if engine is enabled in config");
        }
    }

    private void startMessageDispatcher() {
        logger.info("Message Dispatcher is managed by MassGateway");
    }

    private void startTransportServer() {
        logger.info("Starting transport server");

        MassServerConfig serverConfig = MassServerBuilder.create()
                .withPort(serverPort)
                .withWebSocketPath(webSocketPath)
                .withDispatcherContext(dispatcherContext)
                .build();

        serverStater = new MassServerStater(serverConfig);
        serverStater.start();

        logger.info("Transport server started on port {} (current adapter path={})", serverPort, webSocketPath);
    }

    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    public boolean isRunning() {
        return running.get() && serverStater != null && serverStater.isRunning();
    }

    public PollingWorkerSession openPollingWorkerSession(String workerId) {
        if (pollingWorkerAdapter == null) {
            throw new IllegalStateException("Polling worker adapter is unavailable for this runtime");
        }
        return new PollingWorkerSession(workerId, pollingWorkerAdapter);
    }

    public void publishTaskEvents() {
        requireConfiguredEngine().publishTaskEvents();
    }

    /**
     * @deprecated Mock/bootstrap loaders should be configured explicitly through
     * {@link MassBootstrapDataProvider} and operate on {@link MassRuntimeControl}.
     */
    @Deprecated(forRemoval = false)
    public void loadMockData() {
        MassBootstrapDataProvider bootstrapDataProvider = engineConfig.getBootstrapDataProvider();
        if (bootstrapDataProvider == null) {
            throw new IllegalStateException(
                    "No bootstrap data provider is configured for this runtime. "
                            + "Configure bootstrapDataProvider(...) or call your loader directly with MassRuntimeControl."
            );
        }
        bootstrapDataProvider.loadInto(runtimeControl());
    }

    public MassEngine getEngine() {
        return engine;
    }

    private MassEngine requireConfiguredEngine() {
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this application");
        }
        return engine;
    }

    private MassRuntimeControl runtimeControl() {
        return new MassRuntimeControl() {
            @Override
            public com.xa.mass.base.model.Task createTask(MassTaskCreateRequest request) {
                return requireConfiguredEngine().createTask(toEngineRequest(request));
            }

            @Override
            public com.xa.mass.base.model.Task getTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().getTask(taskId);
            }

            @Override
            public java.util.List<com.xa.mass.base.model.Task> getAllTasks() {
                return requireConfiguredEngine().getTaskManager().getAllTasks();
            }

            @Override
            public boolean approveTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().approveTask(taskId);
            }

            @Override
            public boolean rejectTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().rejectTask(taskId);
            }

            @Override
            public boolean blockTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().blockTask(taskId);
            }

            @Override
            public boolean pauseTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().pauseTask(taskId);
            }

            @Override
            public boolean resumeTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().resumeTask(taskId);
            }

            @Override
            public boolean cancelTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().cancelTask(taskId);
            }

            @Override
            public boolean terminateTask(String taskId, com.xa.mass.base.enums.task.TaskTerminalReason reason) {
                return requireConfiguredEngine().getTaskManager().terminateTask(taskId, reason);
            }

            @Override
            public int appendTaskItems(String taskId, java.util.List<java.util.Map<String, Object>> inputs) {
                return requireConfiguredEngine().getTaskManager().appendTaskItems(taskId, inputs);
            }

            @Override
            public boolean sealTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().sealTask(taskId);
            }

            @Override
            public java.util.List<com.xa.mass.base.model.TaskMsg> getTaskMessages(String taskId) {
                return requireConfiguredEngine().getTaskManager().getTaskMessages(taskId);
            }

            @Override
            public void addWorker(com.xa.mass.base.model.Worker worker) {
                requireConfiguredEngine().addWorker(worker);
            }

            @Override
            public void addWorkerContext(com.xa.mass.base.model.WorkerContext workerContext) {
                requireConfiguredEngine().addWorkerContext(workerContext);
            }

            @Override
            public void replaceDefaultRules(Collection<RuleDefinition> rules) {
                var ruleManager = requireConfiguredEngine().getConfig().getRuleManager();
                ruleManager.clear();
                ruleManager.addDefaultRules(java.util.List.copyOf(rules));
            }

            @Override
            public void publishTaskEvents() {
                MassApplication.this.publishTaskEvents();
            }
        };
    }

    private com.xa.mass.engine.model.TaskCreateRequestDto toEngineRequest(MassTaskCreateRequest request) {
        com.xa.mass.engine.model.TaskCreateRequestDto dto = new com.xa.mass.engine.model.TaskCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setProject(request.getProject());
        dto.setTaskName(request.getTaskName());
        dto.setSharedConfig(request.getSharedConfig());
        dto.setInputs(request.getInputs());
        dto.setRoutingCode(request.getRoutingCode());
        dto.setBatchSize(request.getBatchSize());
        dto.setDefaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount());
        dto.setOpenEnded(request.isOpenEnded());
        dto.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        return dto;
    }
}
